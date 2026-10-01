package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Post;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.EtapaAgente;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.PublishJobStatus;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.PublishJobRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.ai.Redactor;
import com.metricol.api.service.storage.R2StorageService;

/**
 * El agente de punta a punta, con la IA simulada: de una foto subida a una
 * propuesta, y de la propuesta aprobada a una publicación programada.
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
class AgenteServiceTest {

    private static final List<PublishJobStatus> VIVOS = List.of(
            PublishJobStatus.QUEUED, PublishJobStatus.RETRYING, PublishJobStatus.RUNNING);

    @Autowired
    private AgenteService agente;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private MediaAssetRepository assets;

    @Autowired
    private PostRepository posts;

    @Autowired
    private SocialAccountRepository cuentas;

    @Autowired
    private PublishJobRepository jobs;

    @MockitoBean
    private RevisorDeMarca revisor;

    @MockitoBean
    private Redactor redactor;

    @MockitoBean
    private R2StorageService storage;

    @MockitoBean
    private com.metricol.api.service.campaign.LogoSobreFoto logo;

    @MockitoBean
    private com.metricol.api.service.media.HuellaDeImagen huellas;

    @MockitoBean
    private com.metricol.api.service.campaign.CampaignImageService generador;

    @MockitoBean
    private com.metricol.api.service.media.RetoqueDeFoto retoque;

    private Workspace ws;
    private final List<UUID> assetsCreados = new ArrayList<>();
    private final List<UUID> cuentasCreadas = new ArrayList<>();

    @BeforeEach
    void preparar() {
        ws = workspaces.save(Workspace.builder().name("Vivento prueba").giro("Inmobiliaria").build());
        // R2 va doblado: las fotos de prueba son "nuestras" como las de verdad.
        when(storage.esNuestra(anyString())).thenReturn(true);
        // Mockito devuelve 0 y no null para un Long: sin esto todas serían "la misma foto".
        when(huellas.de(any(MediaAsset.class))).thenReturn(null);
        when(redactor.redactar(anyString(), any(), any(), any())).thenReturn(new Redactor.Borrador(
                "Depa con vista al mar", "Vive frente al mar en Cancún.",
                Map.of(Platform.INSTAGRAM, "Vive frente al mar en Cancún. ✨")));
    }

    @AfterEach
    void limpiar() {
        enElWorkspace(() -> {
            posts.findAll().forEach(posts::delete);
            assetsCreados.forEach(id -> assets.findById(id).ifPresent(assets::delete));
            cuentasCreadas.forEach(id -> cuentas.findById(id).ifPresent(cuentas::delete));
        });
        workspaces.deleteById(ws.getId());
    }

    private void enElWorkspace(Runnable accion) {
        TenantIdentifierResolver.comoTenant(ws.getId().toString(), accion);
    }

    private void conInstagram() {
        SocialAccount cuenta = cuentas.save(SocialAccount.builder()
                .platform(Platform.INSTAGRAM).accountName("vivento").status(SocialAccountStatus.CONNECTED).build());
        cuentasCreadas.add(cuenta.getId());
    }

    private MediaAsset foto(String nombre) {
        MediaAsset a = assets.save(MediaAsset.builder()
                .fileName(nombre)
                .url("https://cdn.test/media/" + ws.getId() + "/" + nombre)
                .storageKey("media/" + ws.getId() + "/" + nombre)
                .type(MediaType.IMAGE).contentType("image/jpeg").sizeBytes(1024L)
                .status(MediaAssetStatus.READY).build());
        assetsCreados.add(a.getId());
        return a;
    }

    private void revisaComo(RevisorDeMarca.Veredicto v, String motivo) {
        when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(
                new RevisorDeMarca.Revision(v, motivo, "Un depa con vista al mar", "Presumir la vista", "PRODUCTO"));
    }

    @Test
    @DisplayName("apagado no hace nada: ni revisa ni gasta")
    void apagadoNoHaceNada() {
        enElWorkspace(() -> {
            conInstagram();
            foto("antes.jpg");
            assertThat(agente.vuelta(ws.getId())).isZero();
            assertThat(posts.propuestasDelAgente()).isEmpty();
        });
    }

    @Test
    @DisplayName("una foto que va con la marca termina en propuesta, con fecha, motivo y sus redes")
    void deFotoAPropuesta() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset subida = foto("depa.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "es una propiedad en Cancún");

            assertThat(agente.vuelta(ws.getId())).isEqualTo(1);

            List<PostResponse> propuestas = agente.propuestas();
            assertThat(propuestas).hasSize(1);
            PostResponse p = propuestas.get(0);
            assertThat(p.getStatus()).isEqualTo(PostStatus.DRAFT);
            assertThat(p.isPropuestaAgente()).isTrue();
            assertThat(p.getFechaPropuesta()).isAfter(LocalDateTime.now().plusHours(2));
            assertThat(p.getAgenteMotivo()).contains("Va con tu marca: es una propiedad en Cancún");
            assertThat(p.getTargets()).extracting(t -> t.getPlatform()).containsExactly(Platform.INSTAGRAM);
            assertThat(assets.findById(subida.getId())).get()
                    .extracting(MediaAsset::getAgenteEtapa).isEqualTo(EtapaAgente.PROPUESTA);

            // La misma foto no se vuelve a proponer en la siguiente vuelta.
            assertThat(agente.vuelta(ws.getId())).isZero();
        });
    }

    @Test
    @DisplayName("lo que se subió antes de encenderlo no lo toca")
    void soloLoNuevo() {
        enElWorkspace(() -> {
            conInstagram();
            foto("vieja.jpg");
            agente.encender(ws.getId(), true);
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");
            assertThat(agente.vuelta(ws.getId())).isZero();
        });
    }

    @Test
    @DisplayName("lo dudoso va a observación sin escribir nada, y la persona decide que sí va")
    void observacionYDecision() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset playa = foto("playa.jpg");
            revisaComo(RevisorDeMarca.Veredicto.OBSERVACION, "Parece una foto personal.");

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).isEmpty();
            assertThat(agente.archivos(EtapaAgente.OBSERVACION)).extracting(a -> a.getAgenteMotivo())
                    .containsExactly("Parece una foto personal.");

            agente.decidir(playa.getId(), true, ws.getId());

            assertThat(posts.propuestasDelAgente()).hasSize(1);
            assertThat(posts.propuestasDelAgente().get(0).getAgenteMotivo()).startsWith("Me dijiste que va.");
        });
    }

    @Test
    @DisplayName("aprobar la programa en su fecha y la mete a la cola; descartar la manda a Descartadas")
    void aprobarYDescartar() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset una = foto("una.jpg");
            MediaAsset otra = foto("otra.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");
            agente.vuelta(ws.getId());
            List<Post> propuestas = posts.propuestasDelAgente();
            assertThat(propuestas).hasSize(2);

            Post primera = propuestas.get(0);
            PostResponse aprobada = agente.aprobar(primera.getId(), ws.getId());
            assertThat(aprobada.getStatus()).isEqualTo(PostStatus.SCHEDULED);
            assertThat(aprobada.getScheduledAt()).isEqualTo(primera.getFechaPropuesta());
            assertThat(jobs.existsByPostIdAndStatusIn(primera.getId(), VIVOS)).isTrue();

            Post segunda = propuestas.get(1);
            agente.descartar(segunda.getId());
            assertThat(posts.propuestasDelAgente()).isEmpty();

            UUID deLaSegunda = segunda.getMediaUrls().get(0).endsWith("una.jpg") ? una.getId() : otra.getId();
            assertThat(assets.findById(deLaSegunda)).get()
                    .extracting(MediaAsset::getAgenteEtapa).isEqualTo(EtapaAgente.DESCARTADA);
        });
    }

    @Test
    @DisplayName("si la IA dice que lleva logo, se publica la copia sellada y la original sigue siendo la que cuenta")
    void conLogo() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset depa = foto("depa-logo.jpg");
            String sellada = "https://cdn.test/media/" + ws.getId() + "/con-logo-depa-logo.jpg";
            when(logo.sellar(any(), any(), any())).thenReturn(sellada);
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(new RevisorDeMarca.Revision(
                    RevisorDeMarca.Veredicto.VA, "es tu producto", "Un depa", "Presumir el depa", "PRODUCTO"));

            agente.vuelta(ws.getId());

            Post p = posts.propuestasDelAgente().get(0);
            assertThat(p.getMediaUrls()).containsExactly(sellada);
            assertThat(p.getAgenteMotivo()).contains("Lleva tu logo: es producto");
            assertThat(p.getAgenteTratamiento()).isEqualTo("TAL_CUAL");

            agente.descartar(p.getId());
            assertThat(assets.findById(depa.getId())).get()
                    .extracting(MediaAsset::getAgenteEtapa).isEqualTo(EtapaAgente.DESCARTADA);
        });
    }

    @Test
    @DisplayName("la pausa de emergencia apaga el agente y devuelve lo programado a Por aprobar")
    void pausaDeEmergencia() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("pausa.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");
            agente.vuelta(ws.getId());
            Post p = posts.propuestasDelAgente().get(0);
            agente.aprobar(p.getId(), ws.getId());
            assertThat(posts.propuestasDelAgente()).isEmpty();

            assertThat(agente.pausar(ws.getId())).isEqualTo(1);

            assertThat(agente.estado(ws.getId()).activo()).isFalse();
            assertThat(posts.propuestasDelAgente()).extracting(Post::getId).containsExactly(p.getId());
            assertThat(jobs.existsByPostIdAndStatusIn(p.getId(), VIVOS)).isFalse();
        });
    }

    @Test
    @DisplayName("el horario se guarda y se ve en el estado; sin días no se puede")
    void horario() {
        enElWorkspace(() -> {
            var e = agente.guardarHorario(ws.getId(), List.of(1, 2, 3, 4, 5), 10, 19);
            assertThat(e.dias()).containsExactly(1, 2, 3, 4, 5);
            assertThat(e.horaDesde()).isEqualTo(10);
            assertThat(e.horaHasta()).isEqualTo(19);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> agente.guardarHorario(ws.getId(), List.of(), 9, 20))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    @DisplayName("el resumen de cuentas dice cuánto espera en cada una, y deja fuera las archivadas")
    void resumenDeCuentas() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("r1.jpg");
            foto("r2.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");
            agente.vuelta(ws.getId());
        });
        var mia = new com.metricol.api.models.response.MiWorkspaceResponse(ws.getId(), "Vivento prueba", null, null,
                List.of(), null, List.of(), true, false);
        var archivada = new com.metricol.api.models.response.MiWorkspaceResponse(UUID.randomUUID(), "Vieja", null,
                null, List.of(), null, List.of(), false, true);

        var cuentas = agente.resumen(List.of(archivada, mia));

        assertThat(cuentas).hasSize(1);
        assertThat(cuentas.get(0).propuestas()).isEqualTo(2);
        assertThat(cuentas.get(0).agenteActivo()).isTrue();
        assertThat(cuentas.get(0).actual()).isTrue();
    }

    @Test
    @DisplayName("la misma foto subida dos veces es una propuesta, no dos: la segunda va a Descartadas sin gastar en la IA")
    void repetidas() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("toma-1.jpg");
            MediaAsset segunda = foto("toma-2.jpg");
            when(huellas.de(any(MediaAsset.class))).thenReturn(0x0F0F0F0F0F0F0F0FL);
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).hasSize(1);
            assertThat(assets.findById(segunda.getId())).get().satisfies(a -> {
                assertThat(a.getAgenteEtapa()).isEqualTo(EtapaAgente.DESCARTADA);
                assertThat(a.getAgenteMotivo()).contains("Casi igual a «toma-1.jpg»");
            });
            org.mockito.Mockito.verify(revisor, org.mockito.Mockito.times(1)).revisar(anyString(), any(), anyBoolean());
        });
    }

    @Test
    @DisplayName("una promoción con créditos se diseña con IA: la propuesta lleva el diseño y lo dice")
    void promocionConDiseno() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("2x1.jpg");
            String disenada = "https://cdn.test/media/" + ws.getId() + "/contenido-v1-a-1.jpg";
            when(generador.disenarParaElAgente(any(), any())).thenReturn(new com.metricol.api.service.campaign
                    .CampaignImageService.Diseno(List.of(new com.metricol.api.service.campaign.CampaignImageService
                            .Diseno.Version(List.of(Platform.INSTAGRAM), disenada)),
                            "2x1 en tacos", Map.of(Platform.INSTAGRAM, "Hoy 2x1 en tacos al pastor 🌮")));
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(promoConPrecio());

            agente.vuelta(ws.getId());

            List<PostResponse> propuestas = agente.propuestas();
            assertThat(propuestas).hasSize(1);
            assertThat(propuestas.get(0).getMediaUrls()).containsExactly(disenada);
            assertThat(propuestas.get(0).getTitulo()).isEqualTo("2x1 en tacos");
            assertThat(propuestas.get(0).getAgenteMotivo())
                    .contains("El mensaje tiene que leerse en la imagen")
                    .contains("la diseño con IA");

            // Descartar el diseño le enseña a la cuenta a diseñar menos.
            agente.descartar(propuestas.get(0).getId());
            assertThat(workspaces.findById(ws.getId()).orElseThrow().getAgenteAjusteDiseno()).isEqualTo(1);
        });
    }

    /** Una promoción con precio en una buena foto: el decisor la manda a diseño. */
    private static RevisorDeMarca.Revision promoConPrecio() {
        return new RevisorDeMarca.Revision(RevisorDeMarca.Veredicto.VA, "es tu promoción", "Tacos", "Anunciar el 2x1",
                new DecisorDelAgente.Diagnostico(4, "", true, 4, false, true, DecisorDelAgente.Intencion.VENDER,
                        "PROMOCION"));
    }

    @Test
    @DisplayName("una foto oscura que se corrige se publica retocada, y la original sigue siendo la que cuenta")
    void retoque() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset oscura = foto("oscura.jpg");
            MediaAsset retocada = foto("retocada-oscura.jpg");
            when(retoque.retocar(any(), any())).thenReturn(retocada);
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(new RevisorDeMarca.Revision(
                    RevisorDeMarca.Veredicto.VA, "es tu local", "El local de noche", "Invitar a venir",
                    new DecisorDelAgente.Diagnostico(2, "oscura", true, 4, false, false,
                            DecisorDelAgente.Intencion.INFORMAR, "LUGAR")));

            agente.vuelta(ws.getId());

            Post p = posts.propuestasDelAgente().stream()
                    .filter(x -> oscura.getUrl().equals(x.getAgenteFotoUrl())).findFirst().orElseThrow();
            assertThat(p.getMediaUrls()).containsExactly(retocada.getUrl());
            assertThat(p.getAgenteTratamiento()).isEqualTo("RETOQUE");
            assertThat(p.getAgenteMotivo()).contains("está oscura, pero se corrige").contains("Sin logo");
        });
    }

    @Test
    @DisplayName("si el diseño no sale, la promoción va tal cual: nunca se queda sin propuesta")
    void disenoQueFalla() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset promo = foto("promo.jpg");
            when(generador.disenarParaElAgente(any(), any())).thenThrow(new IllegalStateException("sin imágenes"));
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(promoConPrecio());

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).hasSize(1);
            Post p = posts.propuestasDelAgente().get(0);
            assertThat(p.getMediaUrls()).containsExactly(promo.getUrl());
            assertThat(p.getAgenteMotivo()).contains("El diseño no salió; va tal cual.");
        });
    }

    @Test
    @DisplayName("sin redes conectadas no revisa nada: no hay a dónde proponer")
    void sinRedes() {
        enElWorkspace(() -> {
            agente.encender(ws.getId(), true);
            foto("sola.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");
            assertThat(agente.vuelta(ws.getId())).isZero();
        });
    }
}
