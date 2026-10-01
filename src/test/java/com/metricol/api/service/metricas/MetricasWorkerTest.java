package com.metricol.api.service.metricas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.Organization;
import com.metricol.api.entity.Post;
import com.metricol.api.entity.PostTarget;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.PostTargetStatus;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.social.UploadPostClient;

/** De lo publicado a las métricas guardadas, y de ahí a lo que se aprende. Upload-post va doblado. */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000",
        "app.agente.enabled=false",
        "app.metricas.pausa-ms=0"
})
class MetricasWorkerTest {

    @Autowired private MetricasWorker worker;
    @Autowired private LoQueFunciona loQueFunciona;
    @Autowired private OrganizationRepository organizaciones;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private SocialAccountRepository cuentas;
    @Autowired private PostRepository posts;
    @Autowired private PostTargetRepository destinos;
    @Autowired private NamedParameterJdbcTemplate sql;

    @MockitoBean private UploadPostClient client;

    private Organization org;
    private Workspace ws;
    private final List<UUID> destinosCreados = new ArrayList<>();

    @BeforeEach
    void preparar() {
        org = organizaciones.save(Organization.builder().name("Métricas prueba").build());
        ws = workspaces.save(Workspace.builder().name("Tacos").organization(org).uploadPostProfile("perfil-tacos").build());
    }

    @AfterEach
    void limpiar() {
        String t = ws.getId().toString();
        sql.update("delete from post_targets where post_id in (select id from posts where tenant_id = :t)", Map.of("t", t));
        sql.update("delete from post_media where post_id in (select id from posts where tenant_id = :t)", Map.of("t", t));
        sql.update("delete from posts where tenant_id = :t", Map.of("t", t));
        sql.update("delete from social_accounts where tenant_id = :t", Map.of("t", t));
        workspaces.deleteById(ws.getId());
        organizaciones.deleteById(org.getId());
    }

    private void publicada(String idEnLaRed, LocalDateTime cuando, String texto) {
        TenantIdentifierResolver.comoTenant(ws.getId().toString(), () -> {
            SocialAccount ig = cuentas.findAll().stream().findFirst().orElseGet(() -> cuentas.save(SocialAccount.builder()
                    .platform(Platform.INSTAGRAM).accountName("tacos").status(SocialAccountStatus.CONNECTED).build()));
            Post p = Post.builder().caption(texto).mediaUrls(new ArrayList<>(List.of("https://cdn.test/x.jpg")))
                    .mediaType(MediaType.IMAGE).status(PostStatus.PUBLISHED).publishedAt(cuando).build();
            p.getTargets().add(PostTarget.builder().post(p).socialAccount(ig).status(PostTargetStatus.PUBLISHED)
                    .publishedAt(cuando).externalPostId(idEnLaRed).captionEnviado(texto).build());
            Post guardado = posts.save(p);
            destinosCreados.add(guardado.getTargets().get(0).getId());
        });
    }

    @Test
    @DisplayName("lee lo publicado de los últimos 14 días, lo guarda y la cuenta aprende de ello")
    void deLaRedAlAprendizaje() {
        LocalDateTime base = LocalDateTime.now().minusDays(10).withMinute(0).withSecond(0).withNano(0);
        for (int i = 0; i < 10; i++) {
            boolean noche = i % 2 == 0;
            publicada("ig-" + i, base.plusDays(i).withHour(noche ? 19 : 10),
                    noche ? "Pastor 2x1 #TacosMerida" : "Abrimos #Lunes");
            when(client.metricasDePublicacion(eq("perfil-tacos"), eq("instagram"), eq("ig-" + i)))
                    .thenReturn(Map.of("platforms", Map.of("instagram", Map.of("post_metrics",
                            Map.of("likes", noche ? 120 : 20, "comments", noche ? 9 : 1, "views", 900)))));
        }
        publicada("viejo", LocalDateTime.now().minusDays(30), "Fuera de la ventana");
        when(client.metricasDePublicacion(anyString(), anyString(), eq("viejo"))).thenReturn(Map.of());

        assertThat(worker.vuelta()).isEqualTo(10);

        PostTarget uno = destinos.findById(destinosCreados.get(0)).orElseThrow();
        assertThat(uno.getMeGusta()).isEqualTo(120);
        assertThat(uno.getMetricasEn()).isNotNull();
        assertThat(destinos.findById(destinosCreados.get(10)).orElseThrow().getMetricasEn())
                .as("lo de hace un mes ya no se mide").isNull();

        // Recién leídas: la siguiente vuelta no las vuelve a pedir.
        assertThat(worker.vuelta()).isZero();

        LoQueFunciona.Resumen r = loQueFunciona.resumen(ws.getId());
        assertThat(r.medidas()).isEqualTo(10);
        assertThat(r.horas()).first().isIn("19:00", "18:00", "20:00");
        assertThat(r.hashtagsBuenos()).contains("#tacosmerida");
        assertThat(r.mejores()).hasSize(3).allMatch(d -> d.texto().contains("#TacosMerida"));
    }
}
