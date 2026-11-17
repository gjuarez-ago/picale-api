package com.metricol.api.service.media.subtitulos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class DisenoVideoTest {

    private static final List<Palabra> HABLA = List.of(new Palabra("Hola", 0, 0.4), new Palabra("mundo", 0.4, 0.9));

    @Test
    @DisplayName("Sin diseño, o con uno vacío, el video sale exactamente como siempre")
    void deSiempre() {
        String antes = GuionAss.escribir(HABLA, EstiloSubtitulos.PALABRA, 1080, 1920, "#D4A858", List.of());
        assertThat(GuionAss.escribir(HABLA, EstiloSubtitulos.PALABRA, 1080, 1920, "#D4A858", List.of(), null))
                .isEqualTo(antes);
        assertThat(GuionAss.escribir(HABLA, EstiloSubtitulos.PALABRA, 1080, 1920, "#D4A858", List.of(),
                DisenoVideo.DE_SIEMPRE)).isEqualTo(antes);
        assertThat(DisenoVideo.DE_SIEMPRE.deSiempre()).isTrue();
    }

    @Test
    @DisplayName("La letra y el tamaño cambian la letra del estilo y su altura; las apretadas se compensan")
    void letraYTamano() {
        var grande = new DisenoVideo(null, null, DisenoVideo.Letra.ANTON, DisenoVideo.Tamano.GRANDE, null);
        // 1080 × 0.11 × 1.2 (Anton) × 1.18 (grande) = 168.
        assertThat(GuionAss.escribir(HABLA, EstiloSubtitulos.REBOTE, 1080, 1920, null, List.of(), grande))
                .contains("Style: Default,Anton,168,");
        var chica = new DisenoVideo(null, null, DisenoVideo.Letra.POPPINS, DisenoVideo.Tamano.CHICO, null);
        // 1080 × 0.075 × 0.96 × 0.85 = 66; en rebote Poppins usa su corte más grueso.
        assertThat(GuionAss.escribir(HABLA, EstiloSubtitulos.CAJA, 1080, 1920, null, List.of(), chica))
                .contains("Style: Default,Poppins ExtraBold,66,").contains("Style: Caja,Poppins ExtraBold,66,");
        assertThat(GuionAss.escribir(HABLA, EstiloSubtitulos.REBOTE, 1080, 1920, null, List.of(), chica))
                .contains("Style: Default,Poppins Black,");
        // Con letra grande caben menos letras por trozo.
        List<Palabra> frase = List.of(new Palabra("recordará", 0, 0.4), new Palabra("excusas", 0.4, 0.9));
        assertThat(GuionAss.trozos(frase, EstiloSubtitulos.PALABRA, 1)).hasSize(1);
        assertThat(GuionAss.trozos(frase, EstiloSubtitulos.PALABRA, 1.18)).hasSize(2);
    }

    @Test
    @DisplayName("Un color elegido a mano se respeta tal cual; el letrero toma el suyo y la tinta que se lea")
    void colores() {
        Letrero idea = new Letrero(Letrero.Tipo.CLAVE, "Hoy", 1, 4, null);
        // Azul marino: como color de marca no pasaría el filtro (saldría amarillo); elegido a mano, sí.
        var marino = new DisenoVideo("#1a3a6b", null, null, null, null);
        String ass = GuionAss.escribir(HABLA, EstiloSubtitulos.PALABRA, 1080, 1920, "#1A3A6B", List.of(idea), marino);
        assertThat(ass).contains("{\\c&H006B3A1A&\\fscx112\\fscy112}Hola");
        // El letrero, del mismo color y con tinta blanca porque es oscuro.
        assertThat(ass).contains("\\c&H006B3A1A&\\p1}").contains("\\c&H00FFFFFF&}HOY");

        var letreroBlanco = new DisenoVideo("#FF7AC8", "#FFFFFF", null, null, null);
        String otro = GuionAss.escribir(HABLA, EstiloSubtitulos.PALABRA, 1080, 1920, null, List.of(idea),
                letreroBlanco);
        assertThat(otro).contains("{\\c&H00C87AFF&\\fscx112").contains("\\c&H00FFFFFF&\\p1}")
                .contains("\\c&H00141414&}HOY");
    }

    @Test
    @DisplayName("Se guarda solo lo ajustado, y un color mal escrito se quita en vez de romper el video")
    void guardado() throws Exception {
        var json = new ObjectMapper();
        var diseno = new DisenoVideo("ffd400", "no-es-color", DisenoVideo.Letra.BEBAS, null,
                DisenoVideo.Cantidad.MUCHOS).limpio();
        assertThat(json.writeValueAsString(diseno))
                .isEqualTo("{\"resalte\":\"#FFD400\",\"letra\":\"BEBAS\",\"letreros\":\"MUCHOS\"}");
        assertThat(json.readValue("{\"letra\":\"ANTON\",\"algoNuevo\":1}", DisenoVideo.class).letra())
                .isEqualTo(DisenoVideo.Letra.ANTON);
        assertThat(DisenoVideo.Cantidad.POCOS.ritmo()).isGreaterThan(DisenoVideo.Cantidad.MUCHOS.ritmo());
    }
}
