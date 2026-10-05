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
        "app.agente.enabled=false",
        // Organiza la tanda en la misma vuelta: las pruebas no esperan diez minutos.
        "app.agente.espera-minutos=0"
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

    /** Sin director (nulo) ni mejoras: cada foto sigue el camino de siempre. */
    @MockitoBean
    private com.metricol.api.service.agente.foto.DirectorDeFoto directorDeFoto;

    @MockitoBean
    private com.metricol.api.service.agente.foto.MejoraDeFoto mejoraDeFoto;

    /** Sin perfil deducido (nulo): las reglas de siempre. */
    @MockitoBean
    private com.metricol.api.service.ai.PerfiladorDelNegocio perfilador;

    @MockitoBean
    private com.metricol.api.service.media.MedidorDeVideo medidor;

    @MockitoBean
    private com.metricol.api.service.agente.video.AnalistaDeVideo analista;

    /** Apagados (activo = false): las pruebas que no son de avisos no mandan nada. */
    @MockitoBean
    private com.metricol.api.service.avisos.AvisosPush avisos;

    /** Sin propuesta de la IA (nulo): cada foto sale con la regla de una sola. */
    @MockitoBean
    private OrganizadorDeContenido organizador;

    private MediaAsset video(String nombre) {
        MediaAsset a = assets.save(MediaAsset.builder()
                .fileName(nombre)
                .url("https://cdn.test/media/" + ws.getId() + "/" + nombre)
                .storageKey("media/" + ws.getId() + "/" + nombre)
                .thumbnailUrl("https://cdn.test/derivados/miniaturas/" + nombre + ".jpg")
                .type(MediaType.VIDEO).contentType("video/mp4").sizeBytes(4096L)
                .status(MediaAssetStatus.READY).build());
        assetsCreados.add(a.getId());
        return a;
    }

    @Test
    @DisplayName("un video vertical se propone como Reel, con su duración y mirado por su portada")
    void videoVertical() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset v = video("recorrido.mp4");
            when(medidor.medir(any())).thenReturn(new com.metricol.api.service.media.FfmpegImagen.MedidasVideo(1080, 1920, 20.4));
            when(analista.analizar(any(), org.mockito.ArgumentMatchers.anyDouble(), any(), anyBoolean()))
                    .thenReturn(new com.metricol.api.service.agente.video.AnalisisDeVideo(RevisorDeMarca.Veredicto.VA,
                            "es un recorrido del depa", "Recorren el depa hasta la terraza", "Presumir la vista",
                            "RECORRIDO", 4, "", true, false, false, DecisorDelAgente.Intencion.VENDER, 9.8,
                            List.of(), 0, 20.4, "Bienvenidos, este depa tiene vista al mar"));

            agente.vuelta(ws.getId());

            Post p = posts.propuestasDelAgente().get(0);
            assertThat(p.getFormat()).isEqualTo(com.metricol.api.enums.PostFormat.REEL);
            assertThat(p.getVideoDurationSeconds()).isEqualTo(20);
            assertThat(p.getMediaUrls()).containsExactly(v.getUrl());
            assertThat(p.getPortadaMs()).isEqualTo(9800);
            assertThat(p.getAgenteMotivo()).contains("Lo vi completo y escuché lo que se dice: es un recorrido")
                    .contains("De portada, el segundo 10");
            // Lo que se dice llega a quien escribe el texto.
            org.mockito.Mockito.verify(redactor).redactar(anyString(),
                    org.mockito.ArgumentMatchers.argThat(l -> l.stream().anyMatch(s -> s.contains("vista al mar"))),
                    any(), any());
        });
    }

    @Test
    @DisplayName("un video de más de 90 s, sin editor, va a Observación con el mejor tramo para recortarlo")
    void videoLargo() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset v = video("largo.mp4");
            when(medidor.medir(any())).thenReturn(new com.metricol.api.service.media.FfmpegImagen.MedidasVideo(1080, 1920, 300));
            when(analista.analizar(any(), org.mockito.ArgumentMatchers.anyDouble(), any(), anyBoolean()))
                    .thenReturn(new com.metricol.api.service.agente.video.AnalisisDeVideo(RevisorDeMarca.Veredicto.VA,
                            "va", "Un recorrido largo", "Presumir", "RECORRIDO", 4, "", true, false, false,
                            DecisorDelAgente.Intencion.VENDER, 70, List.of(), 42, 125, ""));

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).isEmpty();
            assertThat(assets.findById(v.getId())).get().satisfies(a -> {
                assertThat(a.getAgenteEtapa()).isEqualTo(EtapaAgente.OBSERVACION);
                assertThat(a.getAgenteMotivo()).contains("del 0:42 al 2:05");
            });
        });
    }

    @Test
    @DisplayName("un video de más de 10 minutos no se analiza: a Observación por el tope de Pícale")
    void videoDeMasDeDiezMinutos() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset v = video("eterno.mp4");
            when(medidor.medir(any())).thenReturn(new com.metricol.api.service.media.FfmpegImagen.MedidasVideo(1080, 1920, 700));

            agente.vuelta(ws.getId());

            assertThat(assets.findById(v.getId())).get().extracting(MediaAsset::getAgenteMotivo).asString()
                    .contains("hasta 10 minutos");
            org.mockito.Mockito.verifyNoInteractions(analista);
        });
    }

    @Test
    @DisplayName("un video horizontal o ilegible va a Observación con el porqué, sin gastar en la IA")
    void videoQueNoSale() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset horizontal = video("horizontal.mp4");
            when(medidor.medir(any())).thenReturn(new com.metricol.api.service.media.FfmpegImagen.MedidasVideo(1920, 1080, 15));

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).isEmpty();
            assertThat(assets.findById(horizontal.getId())).get().satisfies(a -> {
                assertThat(a.getAgenteEtapa()).isEqualTo(EtapaAgente.OBSERVACION);
                assertThat(a.getAgenteMotivo()).contains("horizontal");
            });
            org.mockito.Mockito.verifyNoInteractions(revisor);
        });
    }

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
        when(huellas.deVideo(any(MediaAsset.class), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(null);
        when(huellas.nitidez(any(MediaAsset.class))).thenReturn(null);
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
    @DisplayName("una que alguien ya está revisando no la toma otro: ni el botón ni la vuelta")
    void candado() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset f = foto("ocupada.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");
            LocalDateTime ahora = LocalDateTime.now();
            assertThat(assets.tomar(f.getId(), ahora, ahora.minusMinutes(20))).isEqualTo(1);

            assertThat(agente.vuelta(ws.getId())).isZero();
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> agente.revisarAhora(f.getId(), ws.getId())))
                    .hasMessageContaining("Ya la estoy revisando");
            assertThat(posts.propuestasDelAgente()).isEmpty();

            // Si quien la tenía se cayó, pasado el candado la retoma la vuelta.
            MediaAsset tomada = assets.findById(f.getId()).orElseThrow();
            tomada.setAgenteTomadoEn(ahora.minusMinutes(AgenteService.CANDADO_MINUTOS + 1));
            assets.save(tomada);
            assertThat(agente.vuelta(ws.getId())).isEqualTo(1);
            assertThat(posts.propuestasDelAgente()).hasSize(1);
        });
    }

    @Test
    @DisplayName("una que nunca se puede revisar va a Observación al tercer intento, no se reintenta para siempre")
    void topeDeIntentos() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset f = foto("rota.jpg");
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(null);

            agente.vuelta(ws.getId());
            agente.vuelta(ws.getId());
            assertThat(assets.findById(f.getId())).get()
                    .extracting(MediaAsset::getAgenteEtapa).isEqualTo(EtapaAgente.PENDIENTE);
            agente.vuelta(ws.getId());

            assertThat(assets.findById(f.getId())).get().satisfies(a -> {
                assertThat(a.getAgenteEtapa()).isEqualTo(EtapaAgente.OBSERVACION);
                assertThat(a.getAgenteMotivo()).contains("tras 3 intentos");
            });
            agente.vuelta(ws.getId());
            org.mockito.Mockito.verify(revisor, org.mockito.Mockito.times(3)).revisar(anyString(), any(), anyBoolean());
        });
    }

    @Test
    @DisplayName("no se rehace una propuesta si otra versión de la misma foto ya se aprobó")
    void cambiarConUnaAprobada() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset f = foto("doble.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");
            agente.vuelta(ws.getId());
            Post aprobada = posts.propuestasDelAgente().get(0);
            agente.aprobar(aprobada.getId(), ws.getId());

            // Una segunda propuesta de la misma foto (como una versión de un diseño).
            Post otra = posts.findById(aprobada.getId()).orElseThrow();
            com.metricol.api.models.request.PostSaveRequest pedido = new com.metricol.api.models.request.PostSaveRequest();
            pedido.setCaption("otra versión");
            pedido.setMediaUrls(List.of(f.getUrl()));
            pedido.setFormat(com.metricol.api.enums.PostFormat.PHOTO.name());
            pedido.setSocialAccountIds(cuentasCreadas);
            Post segunda = postService.crearPropuesta(pedido, otra.getScheduledAt().plusDays(1), "v2",
                    f.getUrl(), "TAL_CUAL", "DIA_A_DIA");

            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                    () -> agente.cambiar(segunda.getId(), "sin logo", ws.getId())))
                    .hasMessageContaining("ya está aprobada");
            assertThat(posts.findByIdAndDeletedAtIsNull(segunda.getId())).isPresent();
        });
    }

    @Test
    @DisplayName("decidir no vuelve a preparar una que ya está en Por aprobar")
    void decidirSobreUnaPropuesta() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset f = foto("ya.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");
            agente.vuelta(ws.getId());

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> agente.decidir(f.getId(), true, ws.getId())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> agente.decidir(f.getId(), false, ws.getId())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(posts.propuestasDelAgente()).hasSize(1);
        });
    }

    @Autowired
    private com.metricol.api.service.PostService postService;

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
    @DisplayName("acabado: si descarta la franja, la cuenta aprende; dos veces y ya no las pone")
    void aprendeElAcabado() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(new RevisorDeMarca.Revision(
                    RevisorDeMarca.Veredicto.VA, "es tu obra", "Una obra", "Presumir la obra", "PRODUCTO"));
            when(directorDeFoto.dirigir(anyString(), any(), any())).thenReturn(
                    new com.metricol.api.service.agente.foto.DirectorDeFoto.Direccion(false, List.of(), "", List.of(),
                            "BOTTOM_LEFT", com.metricol.api.service.agente.foto.DirectorDeFoto.TamanoLogo.MEDIANO, "",
                            List.of(), null, "FRANJA", "Obra terminada"));
            when(logo.acabar(any(), any(), any(), any(), anyBoolean()))
                    .thenAnswer(i -> "https://cdn.test/acabada-" + ((MediaAsset) i.getArgument(0)).getFileName());

            for (int vez = 1; vez <= 2; vez++) {
                foto("obra-" + vez + ".jpg");
                agente.vuelta(ws.getId());
                Post p = posts.propuestasDelAgente().get(0);
                assertThat(p.getAgenteAcabado()).isEqualTo("FRANJA");
                agente.descartar(p.getId());
            }
            assertThat(workspaces.findById(ws.getId())).get()
                    .extracting(Workspace::getAgenteAjusteAcabado).isEqualTo(2);

            foto("obra-3.jpg");
            agente.vuelta(ws.getId());
            Post tercera = posts.propuestasDelAgente().get(0);
            assertThat(tercera.getAgenteAcabado()).isEqualTo("LIMPIO");
            assertThat(tercera.getAgenteMotivo()).contains("prefieres las fotos sin adornos");
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
            MediaAsset primera = foto("toma-1.jpg");
            MediaAsset segunda = foto("toma-2.jpg");
            when(huellas.de(any(MediaAsset.class))).thenReturn(0x0F0F0F0F0F0F0F0FL);
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");

            agente.vuelta(ws.getId());

            // Subidas en el mismo instante: cuál se queda depende del orden de la base. Una sola sale.
            assertThat(posts.propuestasDelAgente()).hasSize(1);
            List<MediaAsset> dos = List.of(assets.findById(primera.getId()).orElseThrow(),
                    assets.findById(segunda.getId()).orElseThrow());
            assertThat(dos).extracting(MediaAsset::getAgenteEtapa)
                    .containsExactlyInAnyOrder(EtapaAgente.PROPUESTA, EtapaAgente.DESCARTADA);
            assertThat(dos).filteredOn(a -> a.getAgenteEtapa() == EtapaAgente.DESCARTADA)
                    .allMatch(a -> a.getAgenteMotivo().contains("Casi igual a «toma-"));
            org.mockito.Mockito.verify(revisor, org.mockito.Mockito.times(1)).revisar(anyString(), any(), anyBoolean());
        });
    }

    @Test
    @DisplayName("de una ráfaga sale la más nítida; las demás a Descartadas, y la distinta sale aparte")
    void rafaga() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset movida = foto("rafaga-1.jpg");
            MediaAsset nitida = foto("rafaga-2.jpg");
            MediaAsset otra = foto("fachada.jpg");
            // 8 bits de diferencia: no es la misma foto (6), pero sí la misma ráfaga (12).
            when(huellas.de(any(MediaAsset.class))).thenAnswer(i -> switch (((MediaAsset) i.getArgument(0)).getFileName()) {
                case "rafaga-1.jpg" -> 0x0000000000000000L;
                case "rafaga-2.jpg" -> 0x00000000000000FFL;
                default -> 0xFFFFFFFF00000000L;
            });
            when(huellas.nitidez(any(MediaAsset.class))).thenAnswer(
                    i -> "rafaga-2.jpg".equals(((MediaAsset) i.getArgument(0)).getFileName()) ? 900.0 : 120.0);
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).hasSize(2);
            assertThat(assets.findById(nitida.getId())).get().extracting(MediaAsset::getAgenteEtapa)
                    .isEqualTo(EtapaAgente.PROPUESTA);
            assertThat(assets.findById(otra.getId())).get().extracting(MediaAsset::getAgenteEtapa)
                    .isEqualTo(EtapaAgente.PROPUESTA);
            assertThat(assets.findById(movida.getId())).get().satisfies(a -> {
                assertThat(a.getAgenteEtapa()).isEqualTo(EtapaAgente.DESCARTADA);
                assertThat(a.getAgenteMotivo()).contains("misma ráfaga que «rafaga-2.jpg»");
            });
        });
    }

    @Test
    @DisplayName("dos flyers con la misma plantilla no son ráfaga: cada uno es su mensaje")
    void flyersNoSonRafaga() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("flyer-1.jpg");
            foto("flyer-2.jpg");
            when(huellas.de(any(MediaAsset.class))).thenAnswer(
                    i -> "flyer-1.jpg".equals(((MediaAsset) i.getArgument(0)).getFileName()) ? 0L : 0xFFL);
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(new RevisorDeMarca.Revision(
                    RevisorDeMarca.Veredicto.VA, "va", "Un flyer de deducciones", "Informar",
                    new DecisorDelAgente.Diagnostico(4, "", true, 4, true, true, DecisorDelAgente.Intencion.INFORMAR,
                            "PROMOCION")));

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).hasSize(2);
        });
    }

    @Test
    @DisplayName("el mismo video subido dos veces: el segundo a Descartadas sin pagar por analizarlo")
    void videoRepetido() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset uno = video("recorrido.mp4");
            pausa();
            MediaAsset otro = video("recorrido-otra-vez.mp4");
            when(medidor.medir(any())).thenReturn(new com.metricol.api.service.media.FfmpegImagen.MedidasVideo(1080, 1920, 20.4));
            when(huellas.deVideo(any(MediaAsset.class), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(0x0F0FL);
            when(analista.analizar(any(), org.mockito.ArgumentMatchers.anyDouble(), any(), anyBoolean()))
                    .thenReturn(new com.metricol.api.service.agente.video.AnalisisDeVideo(RevisorDeMarca.Veredicto.VA,
                            "es un recorrido del depa", "Recorren el depa", "Presumir la vista",
                            "RECORRIDO", 4, "", true, false, false, DecisorDelAgente.Intencion.VENDER, 9.8,
                            List.of(), 0, 20.4, ""));

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).hasSize(1);
            assertThat(assets.findById(uno.getId())).get().extracting(MediaAsset::getAgenteEtapa)
                    .isEqualTo(EtapaAgente.PROPUESTA);
            assertThat(assets.findById(otro.getId())).get().satisfies(a -> {
                assertThat(a.getAgenteEtapa()).isEqualTo(EtapaAgente.DESCARTADA);
                assertThat(a.getAgenteMotivo()).contains("Casi igual a «recorrido.mp4»");
            });
            org.mockito.Mockito.verify(analista, org.mockito.Mockito.times(1))
                    .analizar(any(), org.mockito.ArgumentMatchers.anyDouble(), any(), anyBoolean());
        });
    }

    @Test
    @DisplayName("lo que rescatas le enseña al revisor: la siguiente revisión ya sabe que ese tema sí va")
    void rescatarEnsena() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset dudosa = foto("gastos-medicos.jpg");
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(new RevisorDeMarca.Revision(
                    RevisorDeMarca.Veredicto.OBSERVACION, "no es tu servicio principal",
                    "Infografia sobre deducir gastos medicos", "Informar", "OTRO"));
            agente.vuelta(ws.getId());
            assertThat(assets.findById(dudosa.getId())).get().extracting(MediaAsset::getAgenteEtapa)
                    .isEqualTo(EtapaAgente.OBSERVACION);

            agente.decidir(dudosa.getId(), true, ws.getId());
            assertThat(assets.findById(dudosa.getId())).get().extracting(MediaAsset::getAgenteRescatada)
                    .isEqualTo(true);

            foto("deducciones.jpg");
            agente.vuelta(ws.getId());

            org.mockito.ArgumentCaptor<Redactor.Negocio> negocio = org.mockito.ArgumentCaptor.forClass(Redactor.Negocio.class);
            org.mockito.Mockito.verify(revisor, org.mockito.Mockito.atLeastOnce())
                    .revisar(anyString(), negocio.capture(), anyBoolean());
            assertThat(negocio.getValue().loQueSiVa()).contains("deducir gastos medicos");
            assertThat(RevisorDeMarca.contexto(negocio.getValue(), true)).contains("ya te dijo que SI va")
                    .contains("deducir gastos medicos");
        });
    }

    private static void pausa() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void revisaComoTema(String orientacion, boolean efimero, String tema) {
        when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(new RevisorDeMarca.Revision(
                RevisorDeMarca.Veredicto.VA, "es el depa", "Una recámara con vista", "Presumir el depa",
                DecisorDelAgente.Diagnostico.BUENA, orientacion, efimero, tema));
    }

    @Test
    @DisplayName("tres fotos del mismo depa son un carrusel: una propuesta, en el orden del organizador, y aprobarla las aprueba todas")
    void carrusel() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            // Guardadas seguidas pueden caer en el mismo instante: el orden de subida lo fija la pausa.
            MediaAsset sala = foto("sala.jpg");
            pausa();
            MediaAsset recamara = foto("recamara.jpg");
            pausa();
            MediaAsset vista = foto("vista.jpg");
            revisaComoTema("CUADRADA", false, "depa Calle 60");
            when(organizador.proponer(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.isNull()))
                    .thenReturn(List.of(new OrganizadorDeContenido.Grupo(OrganizadorDeContenido.Formato.CARRUSEL,
                            List.of(3, 1, 2), "depa Calle 60", "son del mismo depa")));

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).hasSize(1);
            Post p = posts.propuestasDelAgente().get(0);
            assertThat(p.getMediaUrls()).containsExactly(vista.getUrl(), sala.getUrl(), recamara.getUrl());
            assertThat(p.getAgenteTratamiento()).isEqualTo("CARRUSEL");
            assertThat(p.getAgenteMotivo()).contains("Hice carrusel con 3 fotos de depa Calle 60");
            assertThat(List.of(sala, recamara, vista)).allSatisfy(a -> assertThat(assets.findById(a.getId())).get()
                    .extracting(MediaAsset::getAgenteEtapa).isEqualTo(EtapaAgente.PROPUESTA));

            agente.aprobar(p.getId(), ws.getId());
            assertThat(List.of(sala, recamara, vista)).allSatisfy(a -> assertThat(assets.findById(a.getId())).get()
                    .extracting(MediaAsset::getAgenteEtapa).isEqualTo(EtapaAgente.APROBADA));
        });
    }

    @Test
    @DisplayName("«sepáralas» en un carrusel lo reorganiza con esa instrucción, sin volver a mirar las fotos")
    void separarCarrusel() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("a.jpg");
            foto("b.jpg");
            revisaComoTema("CUADRADA", false, "tacos");
            when(organizador.proponer(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.isNull()))
                    .thenReturn(List.of(new OrganizadorDeContenido.Grupo(OrganizadorDeContenido.Formato.CARRUSEL,
                            List.of(1, 2), "tacos", "")));
            when(organizador.proponer(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq("sepáralas")))
                    .thenReturn(List.of(
                            new OrganizadorDeContenido.Grupo(OrganizadorDeContenido.Formato.POST, List.of(1), "tacos", ""),
                            new OrganizadorDeContenido.Grupo(OrganizadorDeContenido.Formato.POST, List.of(2), "tacos", "")));
            agente.vuelta(ws.getId());
            Post carrusel = posts.propuestasDelAgente().get(0);
            assertThat(carrusel.getMediaUrls()).hasSize(2);

            List<PostResponse> nuevas = agente.cambiar(carrusel.getId(), "sepáralas", ws.getId());

            assertThat(nuevas).hasSize(2).allMatch(n -> n.getMediaUrls().size() == 1);
            assertThat(posts.findByIdAndDeletedAtIsNull(carrusel.getId())).isEmpty();
            // Lo que se vio de cada foto se reutiliza: la IA de visión no se vuelve a llamar.
            org.mockito.Mockito.verify(revisor, org.mockito.Mockito.times(2)).revisar(anyString(), any(), anyBoolean());
        });
    }

    @Test
    @DisplayName("una foto vertical del momento va de historia, con su calendario aparte del feed")
    void historia() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("promo-hoy.jpg");
            revisaComoTema("VERTICAL", true, "2x1 de hoy");

            agente.vuelta(ws.getId());

            Post p = posts.propuestasDelAgente().get(0);
            assertThat(p.getFormat()).isEqualTo(com.metricol.api.enums.PostFormat.STORY);
            assertThat(p.getAgenteMotivo()).contains("Va de historia");
            assertThat(java.util.Set.of(10, 13, 17, 20)).contains(p.getFechaPropuesta().getHour());
            // No ocupa lugar del feed: los huecos del feed no la cuentan.
            assertThat(posts.huecosTomados(LocalDateTime.now())).isEmpty();
        });
    }

    @Test
    @DisplayName("sin tu sí a tiempo, solo esa se mueve al siguiente hueco y queda marcada; las demás no se tocan")
    void vencidaSeMueve() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("sala.jpg");
            pausa();
            foto("fachada.jpg");
            revisaComoTema("CUADRADA", false, "depa");
            agente.vuelta(ws.getId());
            List<Post> dos = posts.propuestasDelAgente();
            assertThat(dos).hasSize(2);

            Post vencida = dos.get(0);
            Post otra = dos.get(1);
            LocalDateTime deLaOtra = otra.getFechaPropuesta();
            vencida.setFechaPropuesta(LocalDateTime.now().minusHours(1));
            posts.save(vencida);

            agente.vuelta(ws.getId());

            Post movida = posts.findById(vencida.getId()).orElseThrow();
            assertThat(movida.getFechaPropuesta()).isAfter(LocalDateTime.now());
            assertThat(movida.getAgenteMovidaVeces()).isEqualTo(1);
            assertThat(movida.getAgenteCaducaEn()).isAfter(LocalDateTime.now().plusDays(13));
            assertThat(posts.findById(otra.getId()).orElseThrow().getFechaPropuesta()).isEqualTo(deLaOtra);
            assertThat(agente.propuestas()).filteredOn(r -> r.getId().equals(vencida.getId()))
                    .allMatch(PostResponse::isAgenteMovida);
        });
    }

    @Test
    @DisplayName("lo del momento sin tu sí en su día no se publica: vuelve a preguntar si todavía va")
    void momentoCaduca() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset promo = foto("promo-hoy.jpg");
            revisaComoTema("VERTICAL", true, "2x1 de hoy");
            agente.vuelta(ws.getId());
            Post p = posts.propuestasDelAgente().get(0);
            // Su caducidad se calcula en la siguiente vuelta: un día.
            agente.vuelta(ws.getId());
            assertThat(posts.findById(p.getId()).orElseThrow().getAgenteCaducaEn())
                    .isBefore(LocalDateTime.now().plusHours(30));

            Post guardada = posts.findById(p.getId()).orElseThrow();
            guardada.setAgenteCaducaEn(LocalDateTime.now().minusMinutes(1));
            posts.save(guardada);
            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).isEmpty();
            assertThat(assets.findById(promo.getId())).get().satisfies(a -> {
                assertThat(a.getAgenteEtapa()).isEqualTo(EtapaAgente.OBSERVACION);
                assertThat(a.getAgenteMotivo()).contains("Era del momento").contains("¿Todavía va?");
            });
        });
    }

    @Test
    @DisplayName("con la fila del sí llena, lo nuevo se revisa y se guarda en reserva; entra al hacerse lugar")
    void filaLlena() {
        Object real = org.springframework.test.util.AopTestUtils.getTargetObject(agente);
        org.springframework.test.util.ReflectionTestUtils.setField(real, "topeFila", 1);
        try {
            enElWorkspace(() -> {
                conInstagram();
                agente.encender(ws.getId(), true);
                foto("sala.jpg");
                revisaComoTema("CUADRADA", false, "depa");
                agente.vuelta(ws.getId());
                Post primera = posts.propuestasDelAgente().get(0);
                assertThat(primera.getAgenteCaducaEn()).as("la caducidad sale desde la primera vuelta").isNotNull();

                MediaAsset otra = foto("fachada.jpg");
                agente.vuelta(ws.getId());
                assertThat(posts.propuestasDelAgente()).hasSize(1);
                assertThat(assets.findById(otra.getId())).get().extracting(MediaAsset::getAgenteEtapa)
                        .isEqualTo(EtapaAgente.ANALIZADA);
                AgenteService.Estado e = agente.estado(ws.getId());
                assertThat(e.enReserva()).isEqualTo(1);
                assertThat(e.porRevisar()).isZero();

                agente.descartar(primera.getId());
                agente.vuelta(ws.getId());
                assertThat(assets.findById(otra.getId())).get().extracting(MediaAsset::getAgenteEtapa)
                        .isEqualTo(EtapaAgente.PROPUESTA);
            });
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(real, "topeFila", 6);
        }
    }

    @Test
    @DisplayName("avisos: lo nuevo en un solo aviso y no más de uno por hora; lo que vence, una vez; de noche, nada")
    void avisos() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("sala.jpg");
            pausa();
            foto("fachada.jpg");
            revisaComoTema("CUADRADA", false, "depa");
            agente.vuelta(ws.getId());
            assertThat(posts.propuestasDelAgente()).hasSize(2);

            when(avisos.activo()).thenReturn(true);
            when(avisos.avisarAlEquipo(any(), anyString(), anyString(), any())).thenReturn(true);
            LocalDateTime hoy10 = LocalDateTime.now().withHour(10).withMinute(0).withSecond(0).withNano(0);

            // De noche no se avisa lo nuevo.
            agente.avisar(workspaces.findById(ws.getId()).orElseThrow(), hoy10.withHour(23));
            org.mockito.Mockito.verify(avisos, org.mockito.Mockito.never())
                    .avisarAlEquipo(any(), anyString(), anyString(), any());

            agente.avisar(workspaces.findById(ws.getId()).orElseThrow(), hoy10);
            org.mockito.Mockito.verify(avisos).avisarAlEquipo(org.mockito.ArgumentMatchers.eq(ws.getId()), anyString(),
                    org.mockito.ArgumentMatchers.eq("Tu asistente te preparó 2 publicaciones. ¿Las revisas?"), any());
            assertThat(posts.propuestasDelAgente()).allMatch(p -> p.getAgenteAvisadaEn() != null);

            // Una nueva a la media hora: espera a que pase la hora.
            Post nueva = posts.propuestasDelAgente().get(0);
            nueva.setAgenteAvisadaEn(null);
            posts.save(nueva);
            agente.avisar(workspaces.findById(ws.getId()).orElseThrow(), hoy10.plusMinutes(30));
            org.mockito.Mockito.verify(avisos, org.mockito.Mockito.times(1))
                    .avisarAlEquipo(any(), anyString(), anyString(), any());

            // Se le acaba el tiempo: aviso urgente, una sola vez.
            Post urgente = posts.findById(nueva.getId()).orElseThrow();
            urgente.setFechaPropuesta(hoy10.plusMinutes(90));
            urgente.setAgenteAvisadaEn(hoy10);
            posts.save(urgente);
            agente.avisar(workspaces.findById(ws.getId()).orElseThrow(), hoy10.plusMinutes(31));
            agente.avisar(workspaces.findById(ws.getId()).orElseThrow(), hoy10.plusMinutes(32));
            org.mockito.Mockito.verify(avisos, org.mockito.Mockito.times(1)).avisarAlEquipo(any(), anyString(),
                    org.mockito.ArgumentMatchers.eq("Una publicación necesita tu sí antes de las 11:30 am."), any());
        });
    }

    @Test
    @DisplayName("una persona hecha con IA se pregunta antes; si dices que va, se propone")
    void personaHechaConIa() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset retrato = foto("retrato.jpg");
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(new RevisorDeMarca.Revision(
                    RevisorDeMarca.Veredicto.VA, "va", "Un hombre de traje frente a una oficina", "Presentar al titular",
                    DecisorDelAgente.Diagnostico.BUENA, "VERTICAL", false, "titular",
                    new RevisorDeMarca.Autenticidad(true, true, false, false)));

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).isEmpty();
            assertThat(assets.findById(retrato.getId())).get().satisfies(a -> {
                assertThat(a.getAgenteEtapa()).isEqualTo(EtapaAgente.OBSERVACION);
                assertThat(a.getAgenteMotivo()).contains("persona hecha con IA");
                assertThat(RevisorDeMarca.pareceIa(a.getAgenteAnalisis())).isTrue();
            });

            agente.decidir(retrato.getId(), true, ws.getId());
            assertThat(posts.propuestasDelAgente()).hasSize(1);
        });
    }

    @Test
    @DisplayName("una que el calendario dejó a más de dos semanas no caduca antes de su hora")
    void lejanaNoCaducaAntes() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("sala.jpg");
            revisaComoTema("CUADRADA", false, "depa");
            agente.vuelta(ws.getId());
            Post p = posts.propuestasDelAgente().get(0);
            // Como si el calendario la hubiera puesto ahí al crearla: sin caducidad todavía.
            LocalDateTime lejos = LocalDateTime.now().plusDays(20).withNano(0);
            p.setFechaPropuesta(lejos);
            p.setAgenteCaducaEn(null);
            posts.save(p);

            agente.vuelta(ws.getId());

            assertThat(posts.findById(p.getId()).orElseThrow().getAgenteCaducaEn()).isEqualTo(lejos.plusDays(3));
        });
    }

    @Test
    @DisplayName("lo normal espera dos semanas; luego también pregunta en vez de salir tarde")
    void normalCaduca() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset sala = foto("sala.jpg");
            revisaComoTema("CUADRADA", false, "depa");
            agente.vuelta(ws.getId());
            Post p = posts.propuestasDelAgente().get(0);
            p.setAgenteCaducaEn(LocalDateTime.now().minusMinutes(1));
            posts.save(p);

            agente.vuelta(ws.getId());

            assertThat(posts.propuestasDelAgente()).isEmpty();
            assertThat(assets.findById(sala.getId())).get().extracting(MediaAsset::getAgenteMotivo).asString()
                    .contains("dos semanas");
        });
    }

    @Test
    @DisplayName("con el agente en pausa, aprobar tarde lo del momento no lo publica: lo dice y pregunta")
    void aprobarTardeLoDelMomento() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset promo = foto("promo-hoy.jpg");
            revisaComoTema("VERTICAL", true, "2x1 de hoy");
            agente.vuelta(ws.getId());
            agente.encender(ws.getId(), false);
            Post p = posts.propuestasDelAgente().get(0);
            p.setAgenteCaducaEn(LocalDateTime.now().minusMinutes(1));
            posts.save(p);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> agente.aprobar(p.getId(), ws.getId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("su día ya pasó");
            assertThat(posts.propuestasDelAgente()).isEmpty();
            assertThat(assets.findById(promo.getId())).get().extracting(MediaAsset::getAgenteEtapa)
                    .isEqualTo(EtapaAgente.OBSERVACION);
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
    @DisplayName("probar a mano: revisa una foto ya, aunque el agente esté apagado, y dice qué hizo")
    void revisarAMano() {
        enElWorkspace(() -> {
            conInstagram();
            MediaAsset vieja = foto("de-antes.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "es tu producto");

            AgenteService.Resultado r = agente.revisarAhora(vieja.getId(), ws.getId());

            assertThat(r.etapa()).isEqualTo("PROPUESTA");
            assertThat(r.motivo()).contains("Va con tu marca");
            assertThat(posts.propuestasDelAgente()).hasSize(1);
            // Ya propuesta: no se vuelve a revisar.
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> agente.revisarAhora(vieja.getId(), ws.getId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Ya la revisé");
        });
    }

    @Test
    @DisplayName("sin cobros, una cuenta normal tiene tope de diseños por semana; el diseño de esta semana lo descuenta")
    void topeSinCobros() {
        enElWorkspace(() -> {
            assertThat(agente.disenosDisponibles(ws)).isEqualTo(AgenteService.TOPE_SEMANAL_SIN_COBROS);
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("tope.jpg");
            when(generador.disenarParaElAgente(any(), any())).thenReturn(new com.metricol.api.service.campaign
                    .CampaignImageService.Diseno(List.of(new com.metricol.api.service.campaign.CampaignImageService
                            .Diseno.Version(List.of(Platform.INSTAGRAM),
                                    "https://cdn.test/media/" + ws.getId() + "/d.jpg")), "x", Map.of()));
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(promoConPrecio());
            agente.vuelta(ws.getId());
            assertThat(agente.disenosDisponibles(ws)).isEqualTo(AgenteService.TOPE_SEMANAL_SIN_COBROS - 1);
        });
    }

    @Test
    @DisplayName("¿Le cambiamos algo?: rehace la propuesta con el cambio, que llega a quien escribe")
    void cambiar() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            MediaAsset depa = foto("cambio.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "es tu producto");
            agente.vuelta(ws.getId());
            Post antes = posts.propuestasDelAgente().get(0);

            List<PostResponse> nuevas = agente.cambiar(antes.getId(), "sin logo, menciona Cancún", ws.getId());

            assertThat(nuevas).hasSize(1);
            assertThat(nuevas.get(0).getId()).isNotEqualTo(antes.getId());
            assertThat(nuevas.get(0).getAgenteMotivo()).contains("«sin logo, menciona Cancún»");
            assertThat(posts.findByIdAndDeletedAtIsNull(antes.getId())).isEmpty();
            org.mockito.Mockito.verify(redactor).redactar(
                    org.mockito.ArgumentMatchers.contains("menciona Cancún"), any(), any(), any());
            // Sin logo: no se pidió la copia sellada para la nueva.
            assertThat(nuevas.get(0).getMediaUrls()).containsExactly(depa.getUrl());
        });
    }

    @Test
    @DisplayName("al descartar, lo que venía después se adelanta al hueco libre y su fecha se corrige")
    void replanearAlDescartar() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("r-1.jpg");
            foto("r-2.jpg");
            when(revisor.revisar(anyString(), any(), anyBoolean())).thenReturn(new RevisorDeMarca.Revision(
                    RevisorDeMarca.Veredicto.VA, "va", "x", "y", "LUGAR"));
            agente.vuelta(ws.getId());
            List<Post> dos = posts.propuestasDelAgente();
            LocalDateTime hueco = dos.get(0).getFechaPropuesta();

            agente.descartar(dos.get(0).getId());

            Post segunda = posts.findById(dos.get(1).getId()).orElseThrow();
            assertThat(segunda.getFechaPropuesta()).isEqualTo(hueco);
            assertThat(segunda.getAgenteMotivo()).contains(
                    java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", java.util.Locale.forLanguageTag("es-MX"))
                            .format(hueco));
        });
    }

    @Test
    @DisplayName("la bandeja de todas las cuentas trae cada propuesta con lo que la persona puede hacer en esa cuenta")
    void bandejaDeTodas() {
        enElWorkspace(() -> {
            conInstagram();
            agente.encender(ws.getId(), true);
            foto("t.jpg");
            revisaComo(RevisorDeMarca.Veredicto.VA, "va");
            agente.vuelta(ws.getId());
        });
        var mia = new com.metricol.api.models.response.MiWorkspaceResponse(ws.getId(), "Vivento prueba", null, "#0035D7",
                List.of(), null, List.of("POST_CREATE", "POST_SCHEDULE"), false, false);

        var bandeja = agente.bandejaDeTodas(List.of(mia));

        assertThat(bandeja).hasSize(1);
        assertThat(bandeja.get(0).cuenta()).isEqualTo("Vivento prueba");
        assertThat(bandeja.get(0).puedeAprobar()).isTrue();
        assertThat(bandeja.get(0).puedeDescartar()).isFalse();
    }

    @Test
    @DisplayName("revisar ahora pide el agente encendido")
    void vueltaAhoraApagado() {
        enElWorkspace(() -> org.assertj.core.api.Assertions.assertThatThrownBy(() -> agente.vueltaAhora(ws.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Enciende el agente"));
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
