package com.metricol.api.service.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Post;
import com.metricol.api.repository.MediaAssetRepository;

/** Qué publicaciones salen con la etiqueta de "hecha con IA". */
class EtiquetaDeIaTest {

    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final PostPublishStore store = new PostPublishStore(null, null, null, null, assets, null);

    private static Post post(String url) {
        Post p = new Post();
        p.setMediaUrls(new ArrayList<>(List.of(url)));
        return p;
    }

    private void hay(MediaAsset... encontrados) {
        when(assets.findByUrlIn(anyList())).thenReturn(List.of(encontrados));
    }

    @Test
    @DisplayName("un diseño que creó la IA lleva la etiqueta")
    void creadaConIa() {
        hay(MediaAsset.builder().url("d").generadaPorIa(true).creadaConIa(true).build());
        assertThat(store.hechaConIa(post("d"))).isTrue();
    }

    @Test
    @DisplayName("una foto real retocada o con logo no la lleva, aunque la haya hecho Pícale")
    void copiaDeFotoReal() {
        hay(MediaAsset.builder().url("r").generadaPorIa(true).build());
        assertThat(store.hechaConIa(post("r"))).isFalse();
    }

    @Test
    @DisplayName("una foto que el revisor vio hecha con IA, y la persona dijo que va, la lleva")
    void vistaComoIa() {
        hay(MediaAsset.builder().url("f").agenteAnalisis("{\"veredicto\":\"VA\",\"pareceIa\":true}").build());
        assertThat(store.hechaConIa(post("f"))).isTrue();
    }

    @Test
    @DisplayName("la copia con logo de una imagen de IA: se mira la original de la propuesta")
    void originalDeLaPropuesta() {
        Post p = post("con-logo");
        p.setAgenteFotoUrl("original");
        hay(MediaAsset.builder().url("con-logo").generadaPorIa(true).build(),
                MediaAsset.builder().url("original").creadaConIa(true).build());
        assertThat(store.hechaConIa(p)).isTrue();
    }
}
