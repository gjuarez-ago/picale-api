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
    @DisplayName("La duración del reel depende de cada red")
    void elReelNoImponeUnTopeGlobal() {
        FormatRulesService.Regla reel = reglas.de(PostFormat.REEL);

        assertThat(reel.maxSegundos()).isNull();
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
    @DisplayName("El video largo ya se ofrece: sin vertical obligatorio y sin tope propio")
    void elVideoLargoSeOfrece() {
        // Estuvo apagado hasta que hubo respuesta para su maximo. Ya la hay:
        // ninguna global, la de cada red. Es lo que admite un video horizontal.
        assertThat(reglas.de(PostFormat.VIDEO).ofrecido()).isTrue();
        assertThat(reglas.de(PostFormat.VIDEO).exigeVertical()).isFalse();
        assertThat(reglas.de(PostFormat.VIDEO).maxSegundos()).isNull();
        assertThat(reglas.ofrecidas())
                .extracting(FormatRulesService.Regla::formato)
                .containsExactlyInAnyOrder(PostFormat.PHOTO, PostFormat.REEL, PostFormat.STORY, PostFormat.VIDEO);
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
