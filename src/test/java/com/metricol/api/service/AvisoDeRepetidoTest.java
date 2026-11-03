package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaType;

class AvisoDeRepetidoTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 2, 19, 10);

    private static MediaAsset subido(MediaType tipo, String nombre, LocalDateTime cuando) {
        return MediaAsset.builder().type(tipo).fileName(nombre).createdAt(cuando).build();
    }

    @Test
    @DisplayName("Dice qué pasó y que no se repitió, sin preguntar nada")
    void unaFraseQueSeEntiendeSola() {
        String aviso = MediaService.avisoDeRepetido(
                subido(MediaType.VIDEO, "WhatsApp Video 7.46.44 AM.mp4", AHORA.minusDays(1)), AHORA);
        assertThat(aviso).isEqualTo("Este video ya está en tu Contenido: lo subiste ayer como "
                + "«WhatsApp Video 7.46.44 AM.mp4». No lo subí otra vez para que no se repita.");
        assertThat(aviso).doesNotContain("?");
    }

    @Test
    @DisplayName("Hoy, ayer o la fecha; foto o video")
    void cuandoYQue() {
        assertThat(MediaService.avisoDeRepetido(subido(MediaType.IMAGE, "a.jpg", AHORA.minusHours(2)), AHORA))
                .startsWith("Esta foto ya está en tu Contenido: lo subiste hoy como «a.jpg»");
        assertThat(MediaService.avisoDeRepetido(subido(MediaType.VIDEO, "b.mp4", AHORA.minusDays(20)), AHORA))
                .contains("lo subiste el 13 de octubre como «b.mp4»");
        assertThat(MediaService.avisoDeRepetido(subido(MediaType.VIDEO, "c.mp4", AHORA.minusYears(1)), AHORA))
                .contains("el 2 de noviembre de 2025");
    }
}
