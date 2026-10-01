package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.metricol.api.entity.Post;
import com.metricol.api.entity.PostTarget;
import com.metricol.api.entity.SocialAccount;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.PostTargetStatus;
import com.metricol.api.enums.PublishJobStatus;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.PublishJobRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.service.publishing.PublishQueueService;
import com.metricol.api.service.storage.R2StorageService;

/**
 * Cancelar y eliminar una publicación, que no es lo mismo que archivarla.
 *
 * <p>Sale de una pregunta real (30 sep 2026): con una publicación programada
 * los únicos botones eran Editar y Archivar, y archivar no la cancela —sale
 * igual en su día—. Lo que importa aquí es que lo cancelado no salga y que lo
 * que ya salió no se pueda "eliminar" dejándolo puesto en las redes.
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
class CancelarYEliminarPostTest {

    private static final String WS = "66666666-6666-6666-6666-666666666666";
    private static final UUID WS_ID = UUID.fromString(WS);

    private static final List<PublishJobStatus> VIVOS = List.of(
            PublishJobStatus.QUEUED, PublishJobStatus.RETRYING, PublishJobStatus.RUNNING);

    @Autowired
    private PostService postService;

    @Autowired
    private PublishQueueService cola;

    @Autowired
    private PostRepository posts;

    @Autowired
    private PublishJobRepository jobs;

    @Autowired
    private SocialAccountRepository cuentas;

    @MockitoBean
    private R2StorageService storage;

    private final List<UUID> postsCreados = new ArrayList<>();
    private final List<UUID> cuentasCreadas = new ArrayList<>();

    @AfterEach
    void limpiar() {
        enElWorkspace(() -> {
            postsCreados.forEach(id -> posts.findById(id).ifPresent(posts::delete));
            cuentasCreadas.forEach(id -> cuentas.findById(id).ifPresent(cuentas::delete));
        });
    }

    private void enElWorkspace(Runnable accion) {
        TenantIdentifierResolver.comoTenant(WS, accion);
    }

    /** Una publicación con un destino por cada estado dado. */
    private Post publicacion(PostStatus estado, PostTargetStatus... destinos) {
        Post post = posts.save(Post.builder()
                .caption("Prueba")
                .mediaUrls(new ArrayList<>(List.of("https://cdn.test/media/" + WS + "/foto.jpg")))
                .mediaType(MediaType.IMAGE)
                .status(estado)
                .scheduledAt(estado == PostStatus.SCHEDULED ? LocalDateTime.now().plusDays(1) : null)
                .build());
        postsCreados.add(post.getId());
        for (PostTargetStatus destino : destinos) {
            SocialAccount cuenta = cuentas.save(SocialAccount.builder()
                    .platform(Platform.INSTAGRAM)
                    .accountName("cuenta-" + destino)
                    .status(SocialAccountStatus.CONNECTED)
                    .build());
            cuentasCreadas.add(cuenta.getId());
            post.getTargets().add(PostTarget.builder()
                    .post(post).socialAccount(cuenta).status(destino).errorMessage("viejo").build());
        }
        return posts.save(post);
    }

    @Test
    @DisplayName("Cancelar una programada la deja en borrador, sin fecha y fuera de la cola")
    void cancelarProgramada() {
        enElWorkspace(() -> {
            Post programada = publicacion(PostStatus.SCHEDULED, PostTargetStatus.QUEUED);
            cola.encolarPara(programada.getId(), WS_ID, LocalDateTime.now().plusDays(1));
            assertThat(jobs.existsByPostIdAndStatusIn(programada.getId(), VIVOS)).isTrue();

            PostResponse hecho = postService.cancel(programada.getId());

            assertThat(hecho.getStatus()).isEqualTo(PostStatus.DRAFT);
            assertThat(hecho.getScheduledAt()).isNull();
            assertThat(jobs.existsByPostIdAndStatusIn(programada.getId(), VIVOS)).isFalse();
            assertThat(postService.get(programada.getId()).getTargets()).isNotEmpty().allSatisfy(t -> {
                assertThat(t.getStatus()).isEqualTo(PostTargetStatus.PENDING);
                assertThat(t.getErrorMessage()).isNull();
            });
            // Sigue en la lista: cancelar no es eliminar.
            assertThat(postService.list().stream().map(PostResponse::getId)).contains(programada.getId());
        });
    }

    @Test
    @DisplayName("No se cancela lo que está saliendo, lo que ya salió en alguna red, ni un borrador")
    void cancelarNoSiempre() {
        enElWorkspace(() -> {
            Post saliendo = publicacion(PostStatus.PUBLISHING, PostTargetStatus.PUBLISHING);
            Post aMedias = publicacion(PostStatus.QUEUED, PostTargetStatus.PUBLISHED, PostTargetStatus.FAILED);
            Post borrador = publicacion(PostStatus.DRAFT);

            assertThatThrownBy(() -> postService.cancel(saliendo.getId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("saliendo");
            assertThatThrownBy(() -> postService.cancel(aMedias.getId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Ya salió");
            assertThatThrownBy(() -> postService.cancel(borrador.getId()))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    @DisplayName("Eliminar una programada la quita de la lista y cancela su trabajo")
    void eliminarProgramada() {
        enElWorkspace(() -> {
            Post programada = publicacion(PostStatus.SCHEDULED, PostTargetStatus.QUEUED);
            cola.encolarPara(programada.getId(), WS_ID, LocalDateTime.now().plusDays(1));

            postService.delete(programada.getId());

            assertThat(postService.list().stream().map(PostResponse::getId)).doesNotContain(programada.getId());
            assertThat(posts.findById(programada.getId())).get()
                    .extracting(Post::getDeletedAt).isNotNull();
            assertThat(jobs.existsByPostIdAndStatusIn(programada.getId(), VIVOS)).isFalse();
        });
    }

    @Test
    @DisplayName("Lo que ya salió en alguna red no se elimina: se archiva")
    void eliminarPublicadaNo() {
        enElWorkspace(() -> {
            Post publicada = publicacion(PostStatus.PUBLISHED, PostTargetStatus.PUBLISHED);

            assertThatThrownBy(() -> postService.delete(publicada.getId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Archívala");
            assertThat(posts.findById(publicada.getId())).get()
                    .extracting(Post::getDeletedAt).isNull();
        });
    }
}
