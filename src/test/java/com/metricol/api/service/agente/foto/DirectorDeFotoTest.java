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

    @Test
    @DisplayName("si hay algo que quitar, se mejora aunque la luz esté bien; y sabe cuándo no vestirla")
    void disenador() throws Exception {
        DirectorDeFoto.Direccion d = director.interpretar("""
                {"mejorar": false, "encargo": "", "quitar": ["the red tape measure on the ground"],
                 "encuadre": {"x": 0.05, "y": 0.1, "ancho": 0.9, "alto": 0.85},
                 "acabado": {"estilo": "FRANJA", "rotulo": "Señalización industrial"}}""");
        assertThat(d.mejorar()).isTrue();
        assertThat(d.encargo()).isNotBlank();
        assertThat(d.quitar()).containsExactly("the red tape measure on the ground");
        assertThat(d.encuadre()).containsExactly(0.05, 0.1, 0.9, 0.85);
        assertThat(d.estilo()).isEqualTo("FRANJA");
        assertThat(MejoraDeFoto.prompt(d)).contains("REMOVE").contains("the red tape measure");

        DirectorDeFoto.Direccion sinRotulo = director.interpretar("""
                {"mejorar": false, "encuadre": {"x": 0.3, "y": 0.3, "ancho": 0.3, "alto": 0.3},
                 "acabado": {"estilo": "FRANJA", "rotulo": ""}}""");
        assertThat(sinRotulo.estilo()).isEqualTo("LIMPIO");
        assertThat(sinRotulo.encuadre()).isNull();
        assertThat(sinRotulo.diseno()).isFalse();
    }

    @Test
    @DisplayName("la revisión de la mejora: fiel no basta, tiene que verse mejor y sin rastros")
    void juicio() throws Exception {
        MejoraDeFoto m = new MejoraDeFoto(null, null, null, null, null, null, null, null);
        MejoraDeFoto.Juicio bueno = m.leerJuicio("{\"fiel\": true, \"mejor\": true}");
        assertThat(bueno.fiel()).isTrue();
        assertThat(bueno.mejor()).isTrue();

        MejoraDeFoto.Juicio conRastro = m.leerJuicio(
                "{\"fiel\": true, \"mejor\": false, \"porque\": \"Quedó una mancha donde estaba la cinta.\"}");
        assertThat(conRastro.fiel()).isTrue();
        assertThat(conRastro.mejor()).isFalse();
        assertThat(conRastro.motivo()).isEqualTo("quedó una mancha donde estaba la cinta");

        // Si no dice que se ve mejor, no se presume.
        assertThat(m.leerJuicio("{\"fiel\": true}").mejor()).isFalse();
        MejoraDeFoto.Juicio inventada = m.leerJuicio("{\"fiel\": false, \"cambios\": \"Agregó una piedra\"}");
        assertThat(inventada.fiel()).isFalse();
        assertThat(inventada.motivo()).isEqualTo("agregó una piedra");
        assertThat(m.leerJuicio("sin json").fiel()).isFalse();
    }

    @Test
    @DisplayName("frase: para una foto personal con frase la acepta larga; sin frase, va limpia")
    void frase() throws Exception {
        DirectorDeFoto.Direccion d = director.interpretar("""
                {"mejorar": false, "acabado": {"estilo": "FRASE",
                 "rotulo": "Lo que se construye con paciencia dura generaciones, y se nota en cada detalle."}}""");
        assertThat(d.estilo()).isEqualTo("FRASE");
        assertThat(d.rotulo()).hasSizeGreaterThan(40);
        assertThat(director.interpretar("{\"acabado\": {\"estilo\": \"FRASE\", \"rotulo\": \"\"}}").estilo())
                .isEqualTo("LIMPIO");
    }
}
