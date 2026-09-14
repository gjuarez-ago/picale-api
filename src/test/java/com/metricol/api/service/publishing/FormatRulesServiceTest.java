package com.metricol.api.service.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;

/**
 * La tabla de formatos es la que decide qué se puede publicar, y la leen dos
 * sitios que no pueden discrepar: la validación del servidor y la pantalla,
 * por {@code GET /publishing/formats}. Lo que se fija aquí son las decisiones
 * de producto, no los topes de las redes — que son otros y más flojos.
 */
class FormatRulesServiceTest {

    private final FormatRulesService reglas = new FormatRulesService();

    @Test
    @DisplayName("Una historia es un solo archivo, de Meta, y de un minuto")
    void laHistoriaEsLaMasEstrecha() {
        FormatRulesService.Regla historia = reglas.de(PostFormat.STORY);

        assertThat(historia.redes())
                .containsExactlyInAnyOrder(Platform.FACEBOOK, Platform.INSTAGRAM);
        assertThat(historia.maxArchivos()).isEqualTo(1);
        assertThat(historia.maxSegundos()).isEqualTo(60);
        assertThat(historia.exigeVertical()).isTrue();

        // El unico formato que acepta las dos clases de archivo: el mismo
        // hueco sirve para una foto o para un video.
        assertThat(historia.admiteFoto()).isTrue();
        assertThat(historia.admiteVideo()).isTrue();
    }

    @Test
    @DisplayName("TikTok y YouTube no tienen historias")
    void lasHistoriasSonSoloDeMeta() {
        assertThat(reglas.admite(PostFormat.STORY, Platform.TIKTOK)).isFalse();
        assertThat(reglas.admite(PostFormat.STORY, Platform.YOUTUBE)).isFalse();
        assertThat(reglas.admite(PostFormat.STORY, Platform.LINKEDIN)).isFalse();
    }

    @Test
    @DisplayName("YouTube no admite fotos, y si admite reels")
    void youtubeSoloVideo() {
        assertThat(reglas.admite(PostFormat.PHOTO, Platform.YOUTUBE)).isFalse();
        assertThat(reglas.admite(PostFormat.REEL, Platform.YOUTUBE)).isTrue();
    }

    @Test
    @DisplayName("Un reel dura minuto y medio en todas, no lo que admita cada red")
    void elReelDuraLoMismoEnTodas() {
        FormatRulesService.Regla reel = reglas.de(PostFormat.REEL);

        // TikTok admitiria diez minutos y LinkedIn treinta. Da igual: el tope
        // es de producto, no de red, y por eso hay un solo numero.
        assertThat(reel.maxSegundos()).isEqualTo(90);
        assertThat(reel.minSegundos()).isEqualTo(3);
        assertThat(reel.redes()).containsExactlyInAnyOrder(Platform.values());
        assertThat(reel.maxArchivos()).isEqualTo(1);
        assertThat(reel.admiteFoto()).isFalse();
    }

    @Test
    @DisplayName("El carrusel se queda en seis, por debajo de lo que admiten las redes")
    void elCarruselSeQuedaEnSeis() {
        FormatRulesService.Regla foto = reglas.de(PostFormat.PHOTO);

        assertThat(foto.maxArchivos()).isEqualTo(6);
        assertThat(foto.admiteVideo()).isFalse();
        // Un carrusel no tiene por que ser vertical.
        assertThat(foto.exigeVertical()).isFalse();
        assertThat(foto.minSegundos()).isNull();
        assertThat(foto.maxSegundos()).isNull();
    }

    @Test
    @DisplayName("El video largo esta previsto pero no se ofrece todavia")
    void elVideoLargoNoSeOfrece() {
        // Esta en la tabla —para que lanzarlo sea encender un booleano— pero
        // fuera de lo que se le puede proponer a alguien. Si esto se rompe, es
        // que se solto sin querer.
        assertThat(reglas.de(PostFormat.VIDEO).ofrecido()).isFalse();
        assertThat(reglas.ofrecidas())
                .extracting(FormatRulesService.Regla::formato)
                .containsExactlyInAnyOrder(PostFormat.PHOTO, PostFormat.REEL, PostFormat.STORY);
    }

    @Test
    @DisplayName("Todo formato del enum tiene su regla")
    void ningunFormatoSeQuedaSinRegla() {
        // `de()` lanza si falta, asi que basta con recorrerlos: sin esto, un
        // formato nuevo pasaria la compilacion y reventaria al publicar.
        for (PostFormat formato : PostFormat.values()) {
            assertThat(reglas.de(formato)).isNotNull();
        }
    }
}
