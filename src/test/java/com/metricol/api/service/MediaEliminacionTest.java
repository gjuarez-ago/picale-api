package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import com.metricol.api.entity.PostTarget;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.PostTargetStatus;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.enums.PublishJobStatus;
import com.metricol.api.exception.ConflictoException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.models.response.MediaEliminadoResponse;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.models.response.PostStatusResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.PublishJobRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.publishing.PublishQueueService;
import com.metricol.api.service.storage.R2StorageService;

/**
 * Eliminar contenido de verdad, y lo que se lleva por delante.
 *
 * <p>La regla que importa es la del archivo en uso: sin confirmar no pasa
 * nada, y al confirmar las publicaciones que lo usaban se eliminan PARA LA
 * PERSONA —desaparecen de sus listas y no salen— pero siguen en la tabla para
 * nosotros. Un fallo ahí es una publicación programada que sale sin foto, o
 * una fila borrada que nadie puede explicar después.
 *
 * <p>R2 va doblado: aquí no hay bucket, y lo que se prueba es la regla, no la
 * red.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000"
})
class MediaEliminacionTest {

    private static final String WS = "55555555-5555-5555-5555-555555555555";
    private static final UUID WS_ID = UUID.fromString(WS);

    private static final List<PublishJobStatus> VIVOS = List.of(
            PublishJobStatus.QUEUED, PublishJobStatus.RETRYING, PublishJobStatus.RUNNING);

    @Autowired
    private MediaService media;

    @Autowired
    private PostService postService;

    @Autowired
    private PublishQueueService cola;

    @Autowired
    private MediaAssetRepository assets;

    @Autowired
    private PostRepository posts;

    @Autowired
    private PublishJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private SocialAccountRepository cuentas;

    @Autowired
    private PostTargetRepository destinos;

    @MockitoBean
    private R2StorageService storage;

    private final List<UUID> assetsCreados = new ArrayList<>();
    private final List<UUID> postsCreados = new ArrayList<>();
    private final List<UUID> workspacesCreados = new ArrayList<>();
    private final List<UUID> cuentasCreadas = new ArrayList<>();

    @AfterEach
    void limpiar() {
        enElWorkspace(() -> {
            postsCreados.forEach(id -> posts.findById(id).ifPresent(posts::delete));
            assetsCreados.forEach(id -> assets.findById(id).ifPresent(assets::delete));
            cuentasCreadas.forEach(id -> cuentas.findById(id).ifPresent(cuentas::delete));
        });
        workspacesCreados.forEach(id -> workspaces.findById(id).ifPresent(workspaces::delete));
    }

    private void enElWorkspace(Runnable accion) {
        TenantIdentifierResolver.comoTenant(WS, accion);
    }

    private MediaAsset subir(String nombre, MediaType tipo, long bytes) {
        MediaAsset asset = assets.save(MediaAsset.builder()
                .fileName(nombre)
                .url("https://cdn.test/media/" + WS + "/" + nombre)
                .storageKey("media/" + WS + "/" + nombre)
                .type(tipo)
                .contentType(tipo == MediaType.VIDEO ? "video/mp4" : "image/jpeg")
                .sizeBytes(bytes)
                .status(MediaAssetStatus.READY)
                .thumbnailUrl(tipo == MediaType.VIDEO ? "https://cdn.test/derivados/miniaturas/" + nombre + ".jpg" : null)
                .build());
        assetsCreados.add(asset.getId());
        return asset;
    }

    private MediaAsset foto(String nombre) {
        return subir(nombre, MediaType.IMAGE, 1024L);
    }

    private Post publicacion(MediaAsset medio, PostStatus estado) {
        Post post = posts.save(Post.builder()
                .caption("Con " + medio.getFileName())
                .mediaUrls(new ArrayList<>(List.of(medio.getUrl())))
                .mediaType(medio.getType())
                .status(estado)
                .scheduledAt(estado == PostStatus.SCHEDULED ? LocalDateTime.now().plusDays(1) : null)
                .publishedAt(estado == PostStatus.PUBLISHED ? LocalDateTime.now().minusDays(1) : null)
                .build());
        postsCreados.add(post.getId());
        return post;
    }

    private List<UUID> idsListados() {
        return postService.list().stream().map(PostResponse::getId).toList();
    }

    @Test
    @DisplayName("Un archivo que nadie usa se elimina de verdad y devuelve su espacio")
    void sinUsarSeEliminaYLiberaEspacio() {
        enElWorkspace(() -> {
            MediaAsset suelto = foto("suelta.jpg");
            long antes = assets.espacioUsado();

            MediaEliminadoResponse hecho = media.eliminar(suelto.getId(), false, WS_ID);

            assertThat(assets.findById(suelto.getId())).isEmpty();
            assertThat(assets.espacioUsado()).isEqualTo(antes - 1024L);
            assertThat(hecho.getPublicacionesEliminadas()).isZero();
            assertThat(hecho.getBytesLiberados()).isEqualTo(1024L);
            verify(storage).deleteByKey(suelto.getStorageKey());
        });
    }

    @Test
    @DisplayName("Un archivo en uso no se elimina sin confirmar: MEDIA_IN_USE y todo sigue igual")
    void enUsoSinConfirmarNoPasaNada() {
        enElWorkspace(() -> {
            MediaAsset usada = foto("en-uso.jpg");
            Post programada = publicacion(usada, PostStatus.SCHEDULED);

            assertThatThrownBy(() -> media.eliminar(usada.getId(), false, WS_ID))
                    .isInstanceOf(ConflictoException.class)
                    .satisfies(ex -> assertThat(((ConflictoException) ex).getCode()).isEqualTo("MEDIA_IN_USE"))
                    .hasMessageContaining("1 publicación")
                    .hasMessageContaining("sin salir");

            assertThat(assets.findById(usada.getId())).isPresent();
            assertThat(posts.findById(programada.getId())).get()
                    .extracting(Post::getDeletedAt).isNull();
        });
    }

    @Test
    @DisplayName("Con confirmación se va el archivo y sus publicaciones quedan eliminadas para la persona, marcadas para nosotros")
    void conConfirmacionSeVanLasPublicaciones() {
        enElWorkspace(() -> {
            MediaAsset usada = foto("en-dos.jpg");
            Post programada = publicacion(usada, PostStatus.SCHEDULED);
            Post publicada = publicacion(usada, PostStatus.PUBLISHED);
            // La programada tiene su trabajo en la cola: eliminarla tiene que
            // cancelarlo, o saldria igual sin foto.
            cola.encolarPara(programada.getId(), WS_ID, LocalDateTime.now().plusDays(1));
            assertThat(jobs.existsByPostIdAndStatusIn(programada.getId(), VIVOS)).isTrue();

            MediaEliminadoResponse hecho = media.eliminar(usada.getId(), true, WS_ID);

            assertThat(hecho.getPublicacionesEliminadas()).isEqualTo(2);
            assertThat(assets.findById(usada.getId())).isEmpty();

            // Para la persona ya no existen: ni en la lista, ni en los estados,
            // ni por su id.
            assertThat(idsListados()).doesNotContain(programada.getId(), publicada.getId());
            assertThat(posts.findEstados().stream().map(PostStatusResponse::getId))
                    .doesNotContain(programada.getId(), publicada.getId());
            assertThatThrownBy(() -> postService.get(programada.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);

            // Para nosotros siguen ahi, marcadas y sin nada por salir.
            assertThat(posts.findById(programada.getId())).get()
                    .extracting(Post::getDeletedAt).isNotNull();
            assertThat(posts.findById(publicada.getId())).get()
                    .extracting(Post::getDeletedAt).isNotNull();
            assertThat(jobs.existsByPostIdAndStatusIn(programada.getId(), VIVOS)).isFalse();
        });
    }

    @Test
    @DisplayName("La galería dice cuántas publicaciones usan cada archivo y cuántas no han salido")
    void laListaTraeLosUsos() {
        enElWorkspace(() -> {
            MediaAsset usada = foto("contada.jpg");
            MediaAsset libre = foto("libre.jpg");
            publicacion(usada, PostStatus.SCHEDULED);
            publicacion(usada, PostStatus.PUBLISHED);

            List<MediaAssetResponse> lista = media.list();

            MediaAssetResponse conUsos = lista.stream().filter(a -> a.getId().equals(usada.getId())).findFirst().orElseThrow();
            assertThat(conUsos.isEnUso()).isTrue();
            assertThat(conUsos.getUsos()).isEqualTo(2);
            assertThat(conUsos.getUsosSinSalir()).isEqualTo(1);

            MediaAssetResponse sinUsos = lista.stream().filter(a -> a.getId().equals(libre.getId())).findFirst().orElseThrow();
            assertThat(sinUsos.isEnUso()).isFalse();
            assertThat(sinUsos.getUsos()).isZero();
        });
    }

    @Test
    @DisplayName("Una publicación eliminada deja de contar como uso: el archivo queda libre")
    void unaEliminadaYaNoCuentaComoUso() {
        enElWorkspace(() -> {
            MediaAsset compartida = foto("compartida.jpg");
            Post borrador = publicacion(compartida, PostStatus.DRAFT);

            postService.delete(borrador.getId());

            MediaAssetResponse vista = media.list().stream()
                    .filter(a -> a.getId().equals(compartida.getId())).findFirst().orElseThrow();
            assertThat(vista.isEnUso()).isFalse();
            // Y ahora se elimina sin preguntar.
            assertThat(media.eliminar(compartida.getId(), false, WS_ID).getPublicacionesEliminadas()).isZero();
        });
    }

    @Test
    @DisplayName("El logotipo del espacio no se elimina desde Contenido")
    void elLogotipoNoSeElimina() {
        enElWorkspace(() -> {
            MediaAsset logo = foto("logo.png");
            Workspace espacio = workspaces.save(Workspace.builder().name("Prueba logo").logoUrl(logo.getUrl()).build());
            workspacesCreados.add(espacio.getId());

            assertThatThrownBy(() -> media.eliminar(logo.getId(), false, espacio.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("logotipo");
            assertThat(assets.findById(logo.getId())).isPresent();
        });
    }

    @Test
    @DisplayName("Una programada eliminada deja de ocupar el cupo diario de su red")
    void unaEliminadaNoOcupaCupo() {
        enElWorkspace(() -> {
            SocialAccount cuenta = cuentas.save(SocialAccount.builder()
                    .platform(Platform.INSTAGRAM)
                    .accountName("cupo-prueba")
                    .status(SocialAccountStatus.CONNECTED)
                    .build());
            cuentasCreadas.add(cuenta.getId());

            MediaAsset usada = foto("cupo.jpg");
            Post programada = publicacion(usada, PostStatus.SCHEDULED);
            programada.getTargets().add(PostTarget.builder()
                    .post(programada).socialAccount(cuenta).status(PostTargetStatus.QUEUED).build());
            posts.save(programada);

            LocalDateTime dia = programada.getScheduledAt().toLocalDate().atStartOfDay();
            java.util.function.LongSupplier comprometidas = () -> destinos.countComprometidas(
                    WS, Platform.INSTAGRAM, List.of(PostStatus.SCHEDULED, PostStatus.QUEUED),
                    List.of(PostTargetStatus.QUEUED, PostTargetStatus.PENDING),
                    dia, dia.plusDays(1), UUID.randomUUID());
            long antes = comprometidas.getAsLong();

            media.eliminar(usada.getId(), true, WS_ID);

            // Ya no va a salir: guardarle el hueco bloquearía ese día sin motivo.
            assertThat(comprometidas.getAsLong()).isEqualTo(antes - 1);
        });
    }

    @Test
    @DisplayName("Liberar un video que todavía está en una programada se niega")
    void liberarConProgramadaSeNiega() {
        enElWorkspace(() -> {
            MediaAsset video = subir("pendiente.mp4", MediaType.VIDEO, 50L * 1024 * 1024);
            publicacion(video, PostStatus.PUBLISHED);
            publicacion(video, PostStatus.SCHEDULED);

            assertThatThrownBy(() -> media.liberarAPeticion(video.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("todavía no");
            assertThat(assets.findById(video.getId())).get()
                    .extracting(MediaAsset::getStatus).isEqualTo(MediaAssetStatus.READY);
        });
    }

    @Test
    @DisplayName("Liberar un video ya publicado devuelve el espacio y conserva la publicación")
    void liberarConservaLaPublicacion() {
        enElWorkspace(() -> {
            MediaAsset video = subir("salio.mp4", MediaType.VIDEO, 50L * 1024 * 1024);
            Post publicada = publicacion(video, PostStatus.PUBLISHED);
            long antes = assets.espacioUsado();

            MediaEliminadoResponse hecho = media.liberarAPeticion(video.getId());

            assertThat(hecho.getBytesLiberados()).isEqualTo(50L * 1024 * 1024);
            assertThat(assets.espacioUsado()).isEqualTo(antes - 50L * 1024 * 1024);
            assertThat(assets.findById(video.getId())).get()
                    .extracting(MediaAsset::getStatus).isEqualTo(MediaAssetStatus.RELEASED);
            // La publicacion sigue siendo suya, con su portada.
            assertThat(idsListados()).contains(publicada.getId());
            assertThat(postService.get(publicada.getId()).getThumbnailUrl()).isEqualTo(video.getThumbnailUrl());
        });
    }
}
