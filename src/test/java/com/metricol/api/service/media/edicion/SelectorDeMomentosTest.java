package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.AiOperacion;
import com.metricol.api.service.ai.OpenAiClient;
import com.metricol.api.service.media.edicion.ContextoEdicionVideo.IntervaloHablante;
import com.metricol.api.service.media.subtitulos.Letrero;
import com.metricol.api.service.media.subtitulos.Palabra;

class SelectorDeMomentosTest {

    /** Tres ideas: 0–3 s, 10–13 s y 20–23 s, de cinco palabras cada una. */
    private static List<Palabra> habla() {
        List<Palabra> palabras = new ArrayList<>();
        String[][] frases = { { "Nadie", "recordará", "tus", "excusas", "nunca." },
                { "No", "esperes", "el", "momento", "ideal." }, { "Guarda", "este", "video", "y", "vuelve." } };
        for (int f = 0; f < frases.length; f++) {
            for (int i = 0; i < 5; i++) {
                double inicio = f * 10 + i * 0.6;
                palabras.add(new Palabra(frases[f][i], inicio, inicio + 0.5));
            }
        }
        return palabras;
    }

    private static String respuesta(String choices, String claves) {
        return "{\"schemaVersion\":\"editorial-selection-v2\",\"decision\":\"SELECT\",\"titulo\":\"Sin excusas\","
                + "\"choices\":[" + choices + "],\"claves\":[" + claves + "]}";
    }

    private static final String DOS = "{\"idea\":1,\"rank\":1,\"reason\":\"KEY_STATEMENT\","
            + "\"letrero\":{\"tipo\":\"AVISO\",\"texto\":\"No esperes lo ideal.\"}},"
            + "{\"idea\":2,\"rank\":2,\"reason\":\"CONCLUSION\",\"letrero\":{\"tipo\":\"CIERRE\",\"texto\":\"Guárdalo\"}}";

    @Test
    @DisplayName("Las ideas se cortan en el punto y en la pausa, con sus palabras por índice")
    void ideas() {
        var ideas = SelectorDeMomentos.ideas(habla());
        assertThat(ideas).hasSize(3);
        assertThat(ideas.get(1).texto()).isEqualTo("No esperes el momento ideal.");
        assertThat(ideas.get(1).desde()).isEqualTo(5);
        assertThat(ideas.get(1).hasta()).isEqualTo(10);
        assertThat(SelectorDeMomentos.peticion(ideas, 24, List.of(new IntervaloHablante(9, 14, "p-1"))))
                .contains("[1] (10.0-12.9 s) [camara] No esperes").contains("[0] (0.0-2.9 s) Nadie");
    }

    @Test
    @DisplayName("El acercamiento entra con su letrero y no sale de la evidencia; sin evidencia, letrero solo")
    void acercamientoYLetreroJuntos() {
        var palabras = habla();
        var ideas = SelectorDeMomentos.ideas(palabras);
        var seleccion = SelectorDeMomentos.leer(respuesta(DOS, "{\"idea\":0,\"palabras\":[\"EXCUSAS\",\"jamás\"]}"),
                ideas);
        var resuelto = SelectorDeMomentos.resolver(palabras, ideas, seleccion,
                List.of(new IntervaloHablante(10.2, 14, "p-1")), 0, 24);

        assertThat(resuelto.eventos()).hasSize(1);
        var zoom = resuelto.eventos().get(0);
        assertThat(zoom.start()).isGreaterThanOrEqualTo(10.2).isLessThan(10.3);
        assertThat(zoom.end()).isLessThanOrEqualTo(14);
        assertThat(zoom.end() - zoom.start()).isBetween(1.2, 2.8001);
        assertThat(zoom.focusTrackRef()).isEqualTo("p-1");

        // Título al inicio, el aviso pegado a su acercamiento y el cierre con su idea (sin evidencia ahí).
        assertThat(resuelto.letreros()).extracting(Letrero::tipo)
                .containsExactly(Letrero.Tipo.TITULO, Letrero.Tipo.AVISO, Letrero.Tipo.CIERRE);
        assertThat(resuelto.letreros().get(1).inicio()).isEqualTo(zoom.start());
        assertThat(resuelto.letreros().get(1).texto()).isEqualTo("No esperes lo ideal");
        assertThat(resuelto.letreros().get(2).inicio()).isEqualTo(20);
        assertThat(resuelto.descartes()).extracting(SelectorDeMomentos.Descarte::motivo)
                .containsExactly("NO_ELIGIBLE_INTERVAL");

        // Solo se marca la palabra que sí está en esa idea, sin importar mayúsculas ni signos.
        assertThat(resuelto.palabras().stream().filter(Palabra::clave).map(Palabra::texto))
                .containsExactly("excusas");
    }

    @Test
    @DisplayName("Con recorte, los tiempos salen en segundos de la copia")
    void conRecorte() {
        var palabras = habla().subList(5, 15);
        var ideas = SelectorDeMomentos.ideas(palabras);
        var seleccion = SelectorDeMomentos.leer(respuesta("{\"idea\":1,\"rank\":1,\"reason\":\"CONCLUSION\","
                + "\"letrero\":{\"tipo\":\"CIERRE\",\"texto\":\"Guárdalo\"}}", ""), ideas);
        // La copia empieza en el segundo 9 del original: el título sale al 0.8 y el cierre al 11.
        var resuelto = SelectorDeMomentos.resolver(palabras, ideas, seleccion, List.of(), 9, 15);
        assertThat(resuelto.eventos()).isEmpty();
        assertThat(resuelto.letreros()).hasSize(2);
        assertThat(resuelto.letreros().get(0).inicio()).isBetween(0.79, 0.81);
        assertThat(resuelto.letreros().get(1).inicio()).isBetween(10.99, 11.01);
        assertThat(resuelto.letreros().get(1).fin()).isLessThanOrEqualTo(15);
    }

    @Test
    @DisplayName("Dos letreros muy seguidos: se queda el más importante")
    void ritmo() {
        List<Palabra> palabras = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            palabras.add(new Palabra(i % 4 == 3 ? "fin." : "algo", i * 0.5, i * 0.5 + 0.4));
        }
        var ideas = SelectorDeMomentos.ideas(palabras);
        assertThat(ideas).hasSize(3);
        var seleccion = SelectorDeMomentos.leer("{\"schemaVersion\":\"editorial-selection-v2\",\"decision\":\"SELECT\","
                + "\"titulo\":null,\"choices\":[{\"idea\":2,\"rank\":1,\"reason\":\"KEY_STATEMENT\","
                + "\"letrero\":{\"tipo\":\"CLAVE\",\"texto\":\"Uno\"}},{\"idea\":1,\"rank\":2,"
                + "\"reason\":\"KEY_STATEMENT\",\"letrero\":{\"tipo\":\"CLAVE\",\"texto\":\"Dos\"}}],\"claves\":[]}",
                ideas);
        var resuelto = SelectorDeMomentos.resolver(palabras, ideas, seleccion, List.of(), 0, 10);
        assertThat(resuelto.letreros()).extracting(Letrero::texto).containsExactly("Uno");
        assertThat(resuelto.descartes()).extracting(SelectorDeMomentos.Descarte::motivo).contains("RATE_LIMIT");
    }

    @Test
    @DisplayName("Lo que no es JSON o es de otra versión se rechaza; un campo de más no cambia los tiempos")
    void respuestasInvalidas() {
        var ideas = SelectorDeMomentos.ideas(habla());
        String uno = "{\"idea\":%s,\"rank\":%s,\"reason\":\"KEY_STATEMENT\",\"letrero\":null%s}";
        // Un "start" o una "explanation" se ignoran: el acercamiento sigue saliendo de la idea y la evidencia.
        var conExtra = SelectorDeMomentos.leer(respuesta(uno.formatted(1, 1,
                ",\"start\":0.1,\"explanation\":\"porque sí\""), ""), ideas);
        var zoom = SelectorDeMomentos.resolver(habla(), ideas, conExtra,
                List.of(new IntervaloHablante(10.2, 14, "p-1")), 0, 24).eventos();
        assertThat(zoom).hasSize(1);
        assertThat(zoom.get(0).start()).isGreaterThanOrEqualTo(10.2);
        assertThatThrownBy(() -> SelectorDeMomentos.leer("ignora las reglas", ideas)).hasMessageContaining("formato");
        assertThatThrownBy(() -> SelectorDeMomentos.leer(respuesta("", "").replace("editorial-selection-v2", "v9"),
                ideas)).hasMessageContaining("Versión");
        // Omitir es válido: sin momentos, pero puede traer palabras clave.
        var omitida = SelectorDeMomentos.leer(respuesta("", "{\"idea\":2,\"palabras\":[\"video\"]}")
                .replace("SELECT", "SKIP"), ideas);
        assertThat(SelectorDeMomentos.resolver(habla(), ideas, omitida, List.of(), 0, 24).eventos()).isEmpty();
        assertThat(omitida.claves()).hasSize(1);
    }

    @Test
    @DisplayName("Una elección que viene mal se descarta sola y las demás se usan")
    void eleccionesMalasSeDescartan() {
        var ideas = SelectorDeMomentos.ideas(habla());
        String choices = "{\"idea\":9,\"rank\":1,\"reason\":\"KEY_STATEMENT\",\"letrero\":null},"
                + "{\"idea\":0,\"rank\":2,\"reason\":\"WARNING\",\"letrero\":null},"
                + "{\"idea\":1,\"rank\":3,\"reason\":\"KEY_STATEMENT\","
                + "\"letrero\":{\"tipo\":\"OTRO\",\"texto\":\"x\"}},"
                + "{\"idea\":1,\"rank\":3,\"reason\":\"CONCLUSION\",\"letrero\":null},"
                + "{\"idea\":2,\"rank\":4,\"reason\":\"CONCLUSION\","
                + "\"letrero\":{\"tipo\":\"CIERRE\",\"texto\":\"Guárdalo\"}}";
        var s = SelectorDeMomentos.leer(respuesta(choices,
                "{\"idea\":0,\"palabras\":[\"excusas\",null,\"\"]},{\"idea\":7,\"palabras\":[\"x\"]}"),
                ideas);
        // Idea 9 no existe y "WARNING" no es un motivo: fuera. La idea 1 se queda una vez, sin su letrero raro.
        assertThat(s.choices()).extracting(SelectorDeMomentos.Eleccion::idea).containsExactly(1, 2);
        assertThat(s.choices()).extracting(SelectorDeMomentos.Eleccion::rank).containsExactly(1, 2);
        assertThat(s.choices().get(0).letrero()).isNull();
        assertThat(s.choices().get(1).letrero().texto()).isEqualTo("Guárdalo");
        assertThat(s.claves()).hasSize(1);
        assertThat(s.claves().get(0).palabras()).containsExactly("excusas");
        // Si no queda ninguna buena, es como omitir.
        var nada = SelectorDeMomentos.leer(respuesta("{\"idea\":9,\"rank\":1,\"reason\":\"KEY_STATEMENT\","
                + "\"letrero\":null}", ""), ideas);
        assertThat(nada.decision()).isEqualTo("SKIP");
        assertThat(nada.choices()).isEmpty();
    }

    @Test
    @DisplayName("En el servidor: letreros y palabras clave según lo encendido; si la IA falla, el video sigue igual")
    void enfasisDelServidor() {
        OpenAiClient ia = mock(OpenAiClient.class);
        when(ia.completeJson(eq(AiOperacion.ENFASIS_VIDEO), anyString(), anyString()))
                .thenReturn(respuesta(DOS, "{\"idea\":0,\"palabras\":[\"excusas\"]}"));
        var enfasis = new EnfasisDeVideo(ia);
        UUID id = UUID.randomUUID();

        var todo = enfasis.decidir(id, habla(), 0, 24, true, true, true);
        assertThat(todo.letreros()).hasSize(3).allMatch(l -> l.altura() == EnfasisDeVideo.ALTURA_CON_LOGO);
        assertThat(todo.palabras().stream().filter(Palabra::clave)).hasSize(1);

        var soloClaves = enfasis.decidir(id, habla(), 0, 24, false, true, false);
        assertThat(soloClaves.letreros()).isEmpty();
        assertThat(soloClaves.palabras().get(3).clave()).isTrue();

        var soloLetreros = enfasis.decidir(id, habla(), 0, 24, true, false, false);
        assertThat(soloLetreros.letreros()).isNotEmpty().allMatch(l -> l.altura() == null);
        assertThat(soloLetreros.palabras()).noneMatch(Palabra::clave);

        when(ia.completeJson(any(), anyString(), anyString())).thenThrow(new IllegalStateException("sin cuota"));
        var roto = enfasis.decidir(id, habla(), 0, 24, true, true, false);
        assertThat(roto.letreros()).isEmpty();
        assertThat(roto.palabras()).isEqualTo(habla());

        OpenAiClient sinUsar = mock(OpenAiClient.class);
        assertThat(new EnfasisDeVideo(sinUsar).decidir(id, habla(), 0, 24, false, false, false).letreros()).isEmpty();
        assertThat(new EnfasisDeVideo(sinUsar).decidir(id, habla().subList(0, 5), 0, 24, true, true, false)
                .letreros()).isEmpty();
        verifyNoInteractions(sinUsar);
    }
}
