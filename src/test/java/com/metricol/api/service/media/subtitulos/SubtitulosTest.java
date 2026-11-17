package com.metricol.api.service.media.subtitulos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Subtítulos con estilo: lo que se lee de Whisper, cómo se corrige y cómo se
 * pinta. Sin ffmpeg ni red: el guion ASS es texto y se revisa como texto.
 */
class SubtitulosTest {

    private static Map<String, Object> palabra(String w, double s, double e) {
        return Map.of("word", w, "start", s, "end", e);
    }

    @Test
    @DisplayName("Whisper inventa 'Amara.org' sobre un tono sin voz: se tira")
    void tiraLoInventado() {
        // La respuesta real del 30 oct 2026 a dos segundos de un tono de 440 Hz.
        Map<String, Object> respuesta = Map.of(
                "segments", List.of(Map.of("start", 0.0, "end", 2.0,
                        "text", " Subtítulos realizados por la comunidad de Amara.org", "no_speech_prob", 0.2)),
                "words", List.of(palabra("Subtítulos", 0, 1.28), palabra("realizados", 1.28, 1.28),
                        palabra("por", 1.28, 1.28), palabra("la", 1.28, 1.28), palabra("comunidad", 1.28, 1.28),
                        palabra("de", 1.28, 1.28), palabra("Amara.org", 1.28, 1.28)));
        assertThat(LecturaDeWhisper.palabras(respuesta)).isEmpty();
    }

    @Test
    @DisplayName("Un tramo marcado como 'aquí no habla nadie' se tira; lo dicho se queda, en orden")
    void tiraElSilencioYConservaLoDicho() {
        Map<String, Object> respuesta = Map.of(
                "segments", List.of(
                        Map.of("start", 0.0, "end", 2.0, "text", " Hola a todos", "no_speech_prob", 0.05),
                        Map.of("start", 5.0, "end", 7.0, "text", " Gracias", "no_speech_prob", 0.9)),
                "words", List.of(palabra(" Hola", 0.2, 0.5), palabra("a", 0.5, 0.6), palabra("todos", 0.4, 1.0),
                        palabra("Gracias", 5.5, 6.0)));
        List<Palabra> palabras = LecturaDeWhisper.palabras(respuesta);
        assertThat(palabras).extracting(Palabra::texto).containsExactly("Hola", "a", "todos");
        // "todos" decía empezar antes que "a": nunca hacia atrás.
        assertThat(palabras.get(2).inicio()).isGreaterThanOrEqualTo(palabras.get(1).inicio());
    }

    @Test
    @DisplayName("Voz con música debajo: 'quizá no habla nadie' pero lo escribió seguro, se queda")
    void vozConMusicaSeQueda() {
        // Los números reales del video 10 (2 nov 2026): perdía sus primeros 25 s de subtítulos.
        Map<String, Object> respuesta = Map.of(
                "segments", List.of(
                        Map.of("start", 1.5, "end", 6.4, "text", " Pierdes clientes", "no_speech_prob", 0.64,
                                "avg_logprob", -0.25),
                        Map.of("start", 8.0, "end", 9.0, "text", " Mmm", "no_speech_prob", 0.64,
                                "avg_logprob", -1.4)),
                "words", List.of(palabra("Pierdes", 1.5, 2.0), palabra("clientes", 2.0, 2.6),
                        palabra("Mmm", 8.2, 8.6)));
        assertThat(LecturaDeWhisper.palabras(respuesta)).extracting(Palabra::texto)
                .containsExactly("Pierdes", "clientes");
    }

    @Test
    @DisplayName("Las frases cortan en las pausas y en lo que se lee de un vistazo")
    void agrupaEnFrases() {
        List<Palabra> palabras = List.of(new Palabra("Hola", 0, 0.3), new Palabra("amigos", 0.3, 0.8),
                new Palabra("hoy", 2.0, 2.2), new Palabra("vendemos", 2.2, 2.7));
        List<Frase> frases = Frases.agrupar(palabras);
        assertThat(frases).extracting(Frase::texto).containsExactly("Hola amigos", "hoy vendemos");
        assertThat(frases.get(1).inicio()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Corregir letras conserva el tiempo de cada palabra; cambiar palabras lo reparte")
    void corregirFrases() {
        Frase frase = Frases.agrupar(List.of(new Palabra("cuida", 1, 1.4), new Palabra("al", 1.4, 1.5),
                new Palabra("sat", 1.5, 2.0))).get(0);

        List<Palabra> letras = Frases.palabras(List.of(new Frase(frase.inicio(), frase.fin(),
                "Cuida al SAT", frase.palabras())));
        assertThat(letras).extracting(Palabra::texto).containsExactly("Cuida", "al", "SAT");
        assertThat(letras.get(2).inicio()).isEqualTo(1.5);

        List<Palabra> otras = Frases.palabras(List.of(new Frase(1, 2, "Ojo con el SAT", frase.palabras()),
                new Frase(3, 4, "   ", List.of())));
        assertThat(otras).extracting(Palabra::texto).containsExactly("Ojo", "con", "el", "SAT");
        assertThat(otras.get(0).inicio()).isEqualTo(1.0);
        assertThat(otras.get(3).fin()).isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("El resalte es el color vivo de la marca que se lee sobre video; si no hay, amarillo")
    void colorDeResalte() {
        // Los de Juan Rodríguez: negro, dorado casi blanco, dorado oscuro y dorado vivo.
        assertThat(GuionAss.resalteLegible("#030202,#F3DA97,#946A29,#D4A858")).isEqualTo("#D4A858");
        assertThat(GuionAss.resalteLegible("#1A3A6B,#000000")).isEqualTo(GuionAss.AMARILLO);
        assertThat(GuionAss.resalteLegible(null)).isEqualTo(GuionAss.AMARILLO);
        assertThat(GuionAss.colorAss("#D4A858")).isEqualTo("&H0058A8D4&");
    }

    @Test
    @DisplayName("Palabra por palabra: un evento por palabra, con la que suena resaltada")
    void estiloPalabra() {
        List<Palabra> palabras = List.of(new Palabra("Hola", 0, 0.4), new Palabra("a", 0.4, 0.5),
                new Palabra("todos", 0.5, 1.0), new Palabra("hoy", 1.0, 1.3));
        String ass = GuionAss.escribir(palabras, EstiloSubtitulos.PALABRA, 1080, 1920, "#D4A858");
        assertThat(ass).contains("PlayResX: 1080").contains("PlayResY: 1920").contains("Montserrat ExtraBold");
        List<String> eventos = ass.lines().filter(l -> l.startsWith("Dialogue:")).toList();
        assertThat(eventos).hasSize(4);
        assertThat(eventos.get(0)).contains("{\\c&H0058A8D4&\\fscx112\\fscy112}Hola{\\r} a todos");
        // "hoy" ya no cabe en el trozo de tres: va solo.
        assertThat(eventos.get(3)).endsWith("{\\c&H0058A8D4&\\fscx112\\fscy112}hoy{\\r}");
    }

    @Test
    @DisplayName("Rebote en mayúsculas con animación; clásico con franja; en vertical, lejos de los botones")
    void estilosReboteYClasico() {
        List<Palabra> palabras = List.of(new Palabra("¿Alguna", 0, 0.4), new Palabra("vez?", 0.4, 0.8));
        String rebote = GuionAss.escribir(palabras, EstiloSubtitulos.REBOTE, 1080, 1920, null);
        assertThat(rebote).contains("Montserrat Black").contains("¿ALGUNA VEZ?").contains("\\t(0,90,");
        String clasico = GuionAss.escribir(palabras, EstiloSubtitulos.CLASICO, 1920, 1080, null);
        // BorderStyle 3 = franja detrás; en horizontal va abajo, como en la tele.
        assertThat(clasico).containsPattern("Style: Default,Montserrat ExtraBold,54,.*,3,.*,2,154,154,76,1");
    }

    @Test
    @DisplayName("Una palabra no puede meter órdenes de ASS, y los tiempos van en centésimas")
    void escapaYFormatea() {
        assertThat(GuionAss.limpio("{\\b1}hola\\N")).isEqualTo("b1holaN");
        assertThat(GuionAss.tiempo(3725.456)).isEqualTo("1:02:05.46");
        assertThat(SubtitulosService.nombreSubtitulado("reel.MOV")).isEqualTo("reel-subtitulado.mp4");
    }

    @Test
    @DisplayName("Caja: la palabra que suena lleva una caja de color detrás y va en tinta oscura encima")
    void estiloCaja() {
        List<Palabra> palabras = List.of(new Palabra("Hola", 0, 0.4), new Palabra("mundo", 0.4, 0.9, true));
        String ass = GuionAss.escribir(palabras, EstiloSubtitulos.CAJA, 1080, 1920, null);
        assertThat(ass).contains("Style: Caja,Montserrat ExtraBold,81,").containsPattern("Style: Caja,.*,3,13\\.0,");
        List<String> eventos = ass.lines().filter(l -> l.startsWith("Dialogue:")).toList();
        // Dos capas por palabra: la caja abajo y el texto arriba.
        assertThat(eventos).hasSize(4);
        assertThat(eventos.get(0)).startsWith("Dialogue: 0,").contains(",Caja,")
                .contains("{\\alpha&HFF&}{\\3a&H00&\\3c&H0000D4FF&}Hola{\\3a&HFF&} mundo");
        // Encima: la que suena en tinta; la clave, en color aunque no le toque.
        assertThat(eventos.get(1)).startsWith("Dialogue: 1,").contains(",Default,")
                .contains("{\\c&H00141414&\\3a&HFF&\\4a&HFF&}Hola{\\r} {\\c&H0000D4FF&}mundo{\\r}");
    }

    @Test
    @DisplayName("Las palabras clave van en color y crecen cuando suenan; sin ellas todo queda como antes")
    void palabrasClave() {
        List<Palabra> palabras = List.of(new Palabra("sin", 0, 0.3), new Palabra("excusas", 0.3, 0.9, true));
        List<String> karaoke = GuionAss.escribir(palabras, EstiloSubtitulos.PALABRA, 1080, 1920, null).lines()
                .filter(l -> l.startsWith("Dialogue:")).toList();
        assertThat(karaoke.get(0)).contains("{\\c&H0000D4FF&\\fscx112\\fscy112}sin{\\r} {\\c&H0000D4FF&}excusas{\\r}");
        assertThat(karaoke.get(1)).contains("\\t(0,90,\\fscx126\\fscy126)}excusas{\\r}");
        String rebote = GuionAss.escribir(palabras, EstiloSubtitulos.REBOTE, 1080, 1920, null);
        assertThat(rebote).contains("SIN {\\c&H0000D4FF&}EXCUSAS{\\c&H00FFFFFF&}");
        String clasico = GuionAss.escribir(palabras, EstiloSubtitulos.CLASICO, 1080, 1920, null);
        assertThat(clasico).contains("sin {\\c&H0000D4FF&}excusas{\\c&H00FFFFFF&}");
    }

    @Test
    @DisplayName("Un letrero: sombra, recuadro redondo a la medida del texto y el texto, entrando juntos")
    void letreros() {
        List<Palabra> palabras = List.of(new Palabra("Hola", 0, 0.4));
        Letrero aviso = new Letrero(Letrero.Tipo.AVISO, "No esperes {\\b1}el momento ideal", 2, 5, null);
        Letrero corto = new Letrero(Letrero.Tipo.CLAVE, "Hoy", 7, 10, 0.3);
        String ass = GuionAss.escribir(palabras, EstiloSubtitulos.PALABRA, 1080, 1920, "#D4A858", List.of(aviso, corto));
        assertThat(ass).contains("Style: Letrero,Montserrat ExtraBold,71,");
        List<String> capas = ass.lines().filter(l -> l.contains(",Letrero,")).toList();
        assertThat(capas).hasSize(6);
        // Sombra, forma y texto del primero, de 2 a 5 s; en dos renglones parejos y sin órdenes coladas.
        assertThat(capas.get(0)).startsWith("Dialogue: 4,0:00:02.00,0:00:05.00").contains("\\blur").contains("\\p1}m ");
        assertThat(capas.get(1)).startsWith("Dialogue: 5,").contains("\\c&H00303BFF&\\p1}m ").contains(" b ");
        assertThat(capas.get(2)).startsWith("Dialogue: 6,").contains("\\an5\\move(540,157,540,221,0,220)")
                .contains("\\fad(140,160)").endsWith("}NO ESPERES B1EL\\NMOMENTO IDEAL");
        // El segundo, en el color de la marca y a la altura que se le pidió (0.3 de 1920 = 576).
        assertThat(capas.get(4)).contains("\\c&H0058A8D4&\\p1}");
        assertThat(capas.get(5)).contains(",576,0,220)").endsWith("}HOY");
        // El recuadro mide lo que el texto más su relleno: "HOY" son 2345 milésimas de un cuadro de 71 × 0.64.
        assertThat(GuionAss.anchoDe("HOY", 71)).isCloseTo(106.6, org.assertj.core.data.Offset.offset(0.1));
        assertThat(GuionAss.renglones("TÚ CARGAS LAS CONSECUENCIAS")).containsExactly("TÚ CARGAS LAS", "CONSECUENCIAS");
        // Sin letreros, ni estilo ni eventos de letrero.
        assertThat(GuionAss.escribir(palabras, EstiloSubtitulos.PALABRA, 1080, 1920, null)).doesNotContain("Letrero");
    }

    @Test
    @DisplayName("Lo guardado antes se lee igual, y una palabra que no es clave se escribe como siempre")
    void palabraCompatible() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.writeValueAsString(new Palabra("hola", 1, 1.5)))
                .isEqualTo("{\"texto\":\"hola\",\"inicio\":1.0,\"fin\":1.5}");
        assertThat(json.readValue("{\"texto\":\"hola\",\"inicio\":1.0,\"fin\":1.5}", Palabra.class).clave()).isFalse();
        String clave = json.writeValueAsString(new Palabra("hola", 1, 1.5, true));
        assertThat(clave).contains("\"clave\":true");
        assertThat(json.readValue(clave, Palabra.class).clave()).isTrue();
    }
}
