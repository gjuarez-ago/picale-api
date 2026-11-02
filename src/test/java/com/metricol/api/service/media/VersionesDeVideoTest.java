package com.metricol.api.service.media;

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
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Post;
import com.metricol.api.enums.EtapaAgente;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.service.media.subtitulos.SubtitulosAutomaticos;
import com.metricol.api.service.storage.R2StorageService;

/** Un video, una sola propuesta del agente, con su versión más nueva. */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "app.publishing.queue.poll-delay-ms=3600000",
        "app.publishing.queue.rescue-delay-ms=3600000",
        "app.scheduling.poll-delay-ms=3600000",
        "app.media.orphan-delay-ms=3600000",
        "app.media.unused-delay-ms=3600000"
})
class VersionesDeVideoTest {

    private static final String WS = "66666666-6666-6666-6666-666666666666";

    @Autowired
    private VersionesDeVideo versiones;

    @Autowired
    private MediaAssetRepository assets;

    @Autowired
    private PostRepository posts;

    @MockitoBean
    private R2StorageService storage;

    private final List<UUID> assetsCreados = new ArrayList<>();
    private final List<UUID> postsCreados = new ArrayList<>();

    @AfterEach
    void limpiar() {
        TenantIdentifierResolver.comoTenant(WS, () -> {
            postsCreados.forEach(id -> posts.findById(id).ifPresent(posts::delete));
            assetsCreados.forEach(id -> assets.findById(id).ifPresent(assets::delete));
        });
    }

    private MediaAsset video(String nombre, UUID de, boolean editada, Double recorteFin) {
        MediaAsset v = assets.save(MediaAsset.builder()
                .fileName(nombre).url("https://cdn.test/" + nombre).storageKey("media/" + WS + "/" + nombre)
                .type(MediaType.VIDEO).contentType("video/mp4").sizeBytes(1024L)
                .thumbnailUrl("https://cdn.test/t-" + nombre + ".jpg").status(MediaAssetStatus.READY)
                .sourceAssetId(de).subtitulos(editada ? "{\"palabras\":[]}" : null)
                .trimStartSeconds(recorteFin == null ? null : 0.0).trimEndSeconds(recorteFin).build());
        assetsCreados.add(v.getId());
        return v;
    }

    private Post propuesta(MediaAsset medio, PostStatus estado) {
        Post p = posts.save(Post.builder().caption("Propuesta").mediaUrls(new ArrayList<>(List.of(medio.getUrl())))
                .mediaType(MediaType.VIDEO).status(estado).propuestaAgente(true).build());
        postsCreados.add(p.getId());
        return p;
    }

    @Test
    @DisplayName("Un recorte a mano descarta la propuesta pendiente del original y espera su edición; lo aprobado no se toca")
    void recorteAMano() {
        TenantIdentifierResolver.comoTenant(WS, () -> {
            MediaAsset original = video("charla.mp4", null, false, null);
            original.setAgenteEtapa(EtapaAgente.PROPUESTA);
            assets.save(original);
            MediaAsset finalVieja = video("charla-subtitulado.mp4", original.getId(), true, null);
            Post pendiente = propuesta(finalVieja, PostStatus.DRAFT);
            Post programada = propuesta(original, PostStatus.SCHEDULED);
            MediaAsset recorte = video("charla-recortado.mp4", original.getId(), false, 70.0);

            versiones.alTerminarRecorte(recorte);

            assertThat(posts.findById(pendiente.getId())).get().extracting(Post::getDeletedAt).isNotNull();
            assertThat(posts.findById(programada.getId())).get().extracting(Post::getDeletedAt).isNull();
            assertThat(assets.findById(original.getId())).get().extracting(MediaAsset::getAgenteEtapa)
                    .isEqualTo(EtapaAgente.DESCARTADA);
            // Sin ajustes guardados la música va encendida: el recorte pasa por la edición.
            assertThat(recorte.getSubtitulosEstado()).isEqualTo(SubtitulosAutomaticos.ESPERANDO);
        });
    }

    @Test
    @DisplayName("Una versión final nueva entra en la propuesta pendiente y la vieja se archiva")
    void versionNueva() {
        TenantIdentifierResolver.comoTenant(WS, () -> {
            MediaAsset original = video("clase.mp4", null, false, null);
            MediaAsset vieja = video("clase-vieja.mp4", original.getId(), true, null);
            Post pendiente = propuesta(vieja, PostStatus.DRAFT);
            MediaAsset nueva = video("clase-nueva.mp4", original.getId(), true, null);

            versiones.alTerminarVersionFinal(nueva);

            Post cambiada = posts.findById(pendiente.getId()).orElseThrow();
            assertThat(cambiada.getMediaUrls()).containsExactly(nueva.getUrl());
            assertThat(cambiada.getThumbnailUrl()).isEqualTo(nueva.getThumbnailUrl());
            assertThat(assets.findById(vieja.getId())).get().extracting(MediaAsset::getArchivedAt).isNotNull();
            assertThat(assets.findById(nueva.getId())).get().extracting(MediaAsset::getArchivedAt).isNull();
        });
    }
}
