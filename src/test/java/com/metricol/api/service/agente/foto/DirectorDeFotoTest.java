package com.metricol.api.service.agente.foto;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.config.OpenAiProperties;

/** Leer al director y preparar su encargo para el modelo de imágenes. */
class DirectorDeFotoTest {

    private final DirectorDeFoto director = new DirectorDeFoto(null, new OpenAiProperties());

    private static final String ENCARGO = "Lift the shadows on the curved seating wall about one stop, neutralize "
            + "the yellow cast to daylight white and straighten the vertical lines.";

    @Test
    @DisplayName("lee la dirección completa: mejora, encargo, conservar y logo")
    void completa() throws Exception {
        DirectorDeFoto.Direccion d = director.interpretar("""
                {"mejorar": true, "deficiencias": ["sombras muy oscuras", "color amarillento"],
                 "encargo": "%s",
                 "conservar": ["the flagstone pattern", "the orange stake"],
                 "logo": {"zona": "bottom_left", "tamano": "GRANDE", "porque": "sobre la grava"}}
                """.formatted(ENCARGO));
        assertThat(d.mejorar()).isTrue();
        assertThat(d.deficienciasEnFrase()).isEqualTo("sombras muy oscuras y color amarillento");
        assertThat(d.logoZona()).isEqualTo("BOTTOM_LEFT");
        assertThat(d.logoTamano()).isEqualTo(DirectorDeFoto.TamanoLogo.GRANDE);
        assertThat(d.conservar()).containsExactly("the flagstone pattern", "the orange stake");
    }

    @Test
    @DisplayName("sin un encargo que valga, no se mejora; una zona inventada cae abajo a la derecha")
    void defensiva() throws Exception {
        DirectorDeFoto.Direccion d = director.interpretar("""
                ```json
                {"mejorar": true, "encargo": "make it nice", "logo": {"zona": "MIDDLE", "tamano": "ENORME"}}
                ```""");
        assertThat(d.mejorar()).isFalse();
        assertThat(d.encargo()).isEmpty();
        assertThat(d.logoZona()).isEqualTo("BOTTOM_RIGHT");
        assertThat(d.logoTamano()).isEqualTo(DirectorDeFoto.TamanoLogo.MEDIANO);
    }

    @Test
    @DisplayName("el prompt de la mejora lleva el encargo, lo que se conserva y las reglas de realismo")
    void prompt() throws Exception {
        DirectorDeFoto.Direccion d = director.interpretar("""
                {"mejorar": true, "encargo": "%s", "conservar": ["the flagstone pattern"]}""".formatted(ENCARGO));
        String prompt = MejoraDeFoto.prompt(d);
        assertThat(prompt).contains(ENCARGO).contains("- the flagstone pattern")
                .contains("Do not add, remove, move").contains("Do not add text, logos");
    }

    @Test
    @DisplayName("el lienzo sigue la proporción de la foto y el resultado vuelve a su encuadre")
    void formato() {
        assertThat(MejoraDeFoto.lienzo(1280, 960)).isEqualTo("1536x1024");
        assertThat(MejoraDeFoto.lienzo(960, 1280)).isEqualTo("1024x1536");
        assertThat(MejoraDeFoto.lienzo(1000, 1000)).isEqualTo("1024x1024");

        BufferedImage generada = new BufferedImage(1536, 1024, BufferedImage.TYPE_INT_RGB);
        for (int y = 500; y < 525; y++) {
            for (int x = 755; x < 780; x++) {
                generada.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        BufferedImage ajustada = MejoraDeFoto.alMismoFormato(generada, 1280, 960);
        assertThat(ajustada.getWidth()).isEqualTo(1365);
        assertThat(ajustada.getHeight()).isEqualTo(1024);
        // Recorte al centro: el centro sigue en el centro.
        assertThat(ajustada.getRGB(1365 / 2, 512)).isEqualTo(Color.WHITE.getRGB());
    }
}
