package com.metricol.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * El texto de una duración, tal como lo lee la persona.
 *
 * <p>Es lo que sale en la tarjeta de cada formato y en los mensajes de
 * rechazo, y la app lo enseña tal cual: si aquí 90 segundos fueran "90 s", el
 * reel diría eso en todas partes.
 */
class VideoLimitsPropertiesTest {

    @Test
    void menosDeUnMinutoVaEnSegundos() {
        assertThat(VideoLimitsProperties.legible(3)).isEqualTo("3 s");
        assertThat(VideoLimitsProperties.legible(45)).isEqualTo("45 s");
    }

    @Test
    void minutosExactosVanSinSegundos() {
        assertThat(VideoLimitsProperties.legible(60)).isEqualTo("1 min");
        assertThat(VideoLimitsProperties.legible(600)).isEqualTo("10 min");
    }

    @Test
    void minutoYMedioSeLeeComoTiempo() {
        assertThat(VideoLimitsProperties.legible(90)).isEqualTo("1:30 min");
        assertThat(VideoLimitsProperties.legible(135)).isEqualTo("2:15 min");
        assertThat(VideoLimitsProperties.legible(65)).isEqualTo("1:05 min");
    }
}
