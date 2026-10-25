package com.metricol.api.service.publishing;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.service.storage.R2StorageService;

/**
 * Una publicación que ya salió y vuelve a la cola no se cierra como fallida.
 *
 * <p>Sale de producción (23 oct 2026): un video salió en YouTube a las 09:36,
 * se editó a las 11:18, la edición lo dejó en cola, la cola no encontró red a
 * la que mandarlo y lo cerró como "falló" con el video puesto en YouTube.
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
class PublicadaVuelveALaColaTest {

    private static final String WS = "77777777-7777-7777-7777-777777777777";
    private static final UUID WS_ID = UUID.fromString(WS);

    @Autowired
    private PostPublishStore store;

    @Autowired
    private PostRepository posts;

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

    @Test
    @DisplayName("En cola pero ya salida en todas sus redes: queda publicada y no se manda nada")
    void yaSalidaEnTodas() {
        enElWorkspace(() -> {
            // La red da igual; Instagram porque la base local de algunas
            // máquinas todavía tiene la restricción de antes de YouTube.
            SocialAccount youtube = cuentas.save(SocialAccount.builder()
                    .platform(Platform.INSTAGRAM)
                    .accountName("canal")
                    .status(SocialAccountStatus.CONNECTED)
                    .build());
            cuentasCreadas.add(youtube.getId());

            Post post = posts.save(Post.builder()
                    .caption("Video")
                    .mediaUrls(new ArrayList<>(List.of("https://cdn.test/media/" + WS + "/video.mp4")))
                    .mediaType(MediaType.VIDEO)
                    .status(PostStatus.QUEUED)
                    .build());
            postsCreados.add(post.getId());
            post.getTargets().add(PostTarget.builder()
                    .post(post).socialAccount(youtube).status(PostTargetStatus.PUBLISHED).build());
            posts.save(post);

            PublishPlan plan = store.preparar(post.getId(), WS_ID);

            assertThat(plan.hayQueEnviar()).isFalse();
            assertThat(posts.findById(post.getId())).get()
                    .extracting(Post::getStatus).isEqualTo(PostStatus.PUBLISHED);
        });
    }
}
