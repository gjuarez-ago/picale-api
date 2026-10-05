package com.metricol.api.service.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.Post;
import com.metricol.api.entity.PostTarget;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.entity.SocialConnectionCheck;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.PostTargetStatus;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.PublishJobRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.repository.SocialConnectionCheckRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.avisos.AvisosPush;

/**
 * Una conexión que caduca: la red se marca y avisa, mientras tanto no se le
 * manda nada, y al reconectarla lo reciente sale solo (Juan Rodríguez, 5 oct 2026).
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000",
        "app.agente.enabled=false"
})
class ConexionesCaducadasTest {

    @Autowired private ConexionesCaducadas conexiones;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private SocialAccountRepository cuentas;
    @Autowired private PostRepository posts;
    @Autowired private SocialConnectionCheckRepository checks;
    @Autowired private PublishJobRepository jobs;
    @Autowired private com.metricol.api.repository.PostTargetRepository destinos;

    @MockitoBean private AvisosPush avisos;

    private Workspace ws;
    private SocialAccount instagram;
    private final List<UUID> creados = new ArrayList<>();

    @BeforeEach
    void preparar() {
        ws = workspaces.save(Workspace.builder().name("Juan prueba").giro("Construcción").build());
        enElWorkspace(() -> instagram = cuentas.save(SocialAccount.builder().platform(Platform.INSTAGRAM)
                .accountName("juan").status(SocialAccountStatus.CONNECTED).build()));
    }

    @AfterEach
    void limpiar() {
        enElWorkspace(() -> {
            jobs.findAll().forEach(jobs::delete);
            creados.forEach(id -> posts.findById(id).ifPresent(posts::delete));
            checks.findAll().forEach(checks::delete);
            cuentas.deleteById(instagram.getId());
        });
        workspaces.deleteById(ws.getId());
    }

    private void enElWorkspace(Runnable r) {
        TenantIdentifierResolver.comoTenant(ws.getId().toString(), r);
    }

    private Post fallida(LocalDateTime cuando, String motivo) {
        Post p = Post.builder().caption("Obra terminada").status(PostStatus.FAILED).scheduledAt(cuando).build();
        PostTarget t = PostTarget.builder().post(p).socialAccount(instagram).status(PostTargetStatus.FAILED)
                .errorMessage(motivo).build();
        p.getTargets().add(t);
        Post guardada = posts.save(p);
        creados.add(guardada.getId());
        return guardada;
    }

    @Test
    @DisplayName("falla por conexión: la red queda por reconectar, avisa una vez y la franja cuenta lo pendiente")
    void marcar() {
        enElWorkspace(() -> {
            fallida(LocalDateTime.now().minusHours(2), ConexionesCaducadas.CADUCO);
            fallida(LocalDateTime.now().minusHours(1), ConexionesCaducadas.esperando(Platform.INSTAGRAM));

            conexiones.marcarFallo(Platform.INSTAGRAM, ws.getId());
            conexiones.marcarFallo(Platform.INSTAGRAM, ws.getId());

            assertThat(conexiones.necesitaReconectar(Platform.INSTAGRAM)).isTrue();
            assertThat(conexiones.necesitaReconectar(Platform.FACEBOOK)).isFalse();
            verify(avisos, times(1)).avisarAlEquipo(any(), anyString(), anyString(), any());
            assertThat(conexiones.porReconectar(ws.getId()))
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.red()).isEqualTo("instagram");
                        assertThat(r.pendientes()).isEqualTo(2);
                    });
        });
    }

    @Autowired private RecordatorioDeReconexion recordatorio;

    @Test
    @DisplayName("mientras siga por reconectar: un recordatorio al día, no antes de un día de detectado")
    void recordatorioDiario() {
        enElWorkspace(() -> {
            fallida(LocalDateTime.now().minusHours(2), ConexionesCaducadas.CADUCO);
            conexiones.marcarFallo(Platform.INSTAGRAM, ws.getId());
            org.mockito.Mockito.when(avisos.avisarAlEquipo(any(), anyString(), anyString(), any())).thenReturn(true);
            LocalDateTime ahora = LocalDateTime.now();

            // Recién detectada: ya salió el primer aviso; el recordatorio espera.
            recordatorio.recordar(ws.getId(), ahora);
            verify(avisos, times(1)).avisarAlEquipo(any(), anyString(), anyString(), any());

            SocialConnectionCheck check = checks.findByPlatform("instagram").orElseThrow();
            check.setFalloPorConexionEn(ahora.minusHours(25));
            checks.save(check);
            recordatorio.recordar(ws.getId(), ahora);
            recordatorio.recordar(ws.getId(), ahora.plusHours(1));
            verify(avisos, times(1)).avisarAlEquipo(any(), org.mockito.ArgumentMatchers.eq("Instagram sigue por reconectar"),
                    org.mockito.ArgumentMatchers.contains("1 publicación espera"), any());
        });
    }

    @Test
    @DisplayName("al reconectar: lo reciente vuelve a la cola solo; lo de hace más de 7 días pregunta")
    void reconectar() {
        enElWorkspace(() -> {
            Post reciente = fallida(LocalDateTime.now().minusDays(1), ConexionesCaducadas.CADUCO);
            Post vieja = fallida(LocalDateTime.now().minusDays(10), ConexionesCaducadas.CADUCO);
            conexiones.marcarFallo(Platform.INSTAGRAM, ws.getId());

            // Abrir el enlace sin haber reconectado todavía no desmarca nada.
            SocialConnectionCheck check = checks.findByPlatform("instagram").orElseThrow();
            assertThat(conexiones.alVerificar(check, false, ws.getId())).isTrue();

            conexiones.reconectando(List.of("instagram"));
            check = checks.findByPlatform("instagram").orElseThrow();
            boolean sigue = conexiones.alVerificar(check, false, ws.getId());
            checks.save(check);

            assertThat(sigue).isFalse();
            assertThat(conexiones.necesitaReconectar(Platform.INSTAGRAM)).isFalse();
            assertThat(posts.findById(reciente.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.QUEUED);
            assertThat(jobs.findAll()).hasSize(1);
            assertThat(destinos.findAll()).filteredOn(t -> t.getPost().getId().equals(vieja.getId()))
                    .singleElement()
                    .satisfies(t -> assertThat(t.getErrorMessage()).contains("si todavía va, toca Reintentar"));
        });
    }
}
