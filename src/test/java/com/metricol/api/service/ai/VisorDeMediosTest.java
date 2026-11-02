package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.controller.AiControllerEncargo;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;

/** Pícale descubre de qué se trata un archivo sin que nadie se lo cuente. */
class VisorDeMediosTest {

    @Test
    @DisplayName("De un video mira la portada (no el mp4) y lee lo que se dice; de una versión final, lo del original")
    void video() {
        OpenAiClient ia = mock(OpenAiClient.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        MediaAsset original = MediaAsset.builder().id(UUID.randomUUID()).type(MediaType.VIDEO)
                .url("https://cdn.test/o.mp4").transcripcion(
                        "[{\"texto\":\"Hoy\",\"inicio\":0,\"fin\":1},{\"texto\":\"2x1\",\"inicio\":1,\"fin\":2}]")
                .build();
        MediaAsset final_ = MediaAsset.builder().id(UUID.randomUUID()).type(MediaType.VIDEO)
                .url("https://cdn.test/f.mp4").thumbnailUrl("https://cdn.test/f.jpg")
                .sourceAssetId(original.getId()).build();
        when(assets.findByUrlIn(any())).thenReturn(List.of(final_));
        when(assets.findById(original.getId())).thenReturn(Optional.of(original));
        when(ia.describeImages(eq(AiOperacion.DESCRIBIR_IMAGENES), anyString(), anyString(), any()))
                .thenReturn("Un hombre habla en una terraza.");

        List<String> visto = new VisorDeMedios(ia, assets).describir(List.of(final_.getUrl()));

        verify(ia).describeImages(eq(AiOperacion.DESCRIBIR_IMAGENES), anyString(), anyString(),
                eq(List.of("https://cdn.test/f.jpg")));
        assertThat(visto).contains("En el video se dice: Hoy 2x1", "Un hombre habla en una terraza.");
        assertThat(final_.getDescripcionIa()).isEqualTo("Un hombre habla en una terraza.");
    }

    @Test
    @DisplayName("Sin contexto, el encargo pide descubrirlo del archivo; sin archivo, se dice qué falta")
    void encargo() {
        assertThat(AiControllerEncargo.de("Anuncia el 2x1", List.of())).isEqualTo("Anuncia el 2x1");
        assertThat(AiControllerEncargo.de(" ", List.of("Una foto de tacos.")))
                .contains("descubre de que se trata");
        assertThatThrownBy(() -> AiControllerEncargo.de(null, List.of()))
                .hasMessageContaining("Agrega una foto o un video");
    }
}
