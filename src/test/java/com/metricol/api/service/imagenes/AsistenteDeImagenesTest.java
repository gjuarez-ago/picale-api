package com.metricol.api.service.imagenes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.enums.AiOperacion;
import com.metricol.api.service.agente.RevisorDeMarca;
import com.metricol.api.service.ai.MarcaDelNegocio;
import com.metricol.api.service.ai.OpenAiClient;

/**
 * El que conversa para crear una imagen.
 *
 * <p>Lo que se prueba aquí es lo que decide si esto vale la pena: que lo que
 * sabe del negocio le llegue, que no se cuele lo que la marca prohíbe, y que
 * una respuesta rota del modelo no deje la pantalla muda.
 */
class AsistenteDeImagenesTest {

    private OpenAiClient client;
    private AsistenteDeImagenes asistente;

    private static final MarcaDelNegocio MARCA = new MarcaDelNegocio(
            "Tacos al pastor y suadero", "vecinos del barrio", List.of(), "sin personas en las fotos",
            null, null, null);

    @BeforeEach
    void preparar() {
        client = mock(OpenAiClient.class);
        asistente = new AsistenteDeImagenes(client);
    }

    private void contesta(String json) {
        when(client.completeJson(eq(AiOperacion.ASISTENTE_IMAGEN), anyString(), anyString()))
                .thenReturn(json);
    }

    private String ultimoPrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).completeJson(any(), anyString(), captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------- lo que sabe del negocio

    @Test
    @DisplayName("lo que la marca pide evitar viaja como regla dura, no como sugerencia")
    void loQueLaMarcaEvita() {
        contesta("{\"mensaje\":\"va\",\"opciones\":[\"Sí\"],\"ficha\":{},\"veredicto\":\"VA\"}");

        asistente.hablar("algo para el 2x1", FichaDeImagen.vacia(), List.of(), MARCA, null, List.of());

        assertThat(ultimoPrompt())
                .contains("NUNCA hagas esto")
                .contains("sin personas en las fotos");
    }

    @Test
    @DisplayName("las redes ya conectadas van en el prompt: no se preguntan")
    void noPreguntaLoQueYaSabe() {
        contesta("{\"mensaje\":\"va\",\"opciones\":[\"Sí\"],\"ficha\":{},\"veredicto\":\"VA\"}");

        asistente.hablar("hola", FichaDeImagen.vacia(), List.of(), MARCA, "- Se llama: Tacos El Güero\n",
                List.of("INSTAGRAM", "FACEBOOK"));

        String p = ultimoPrompt();
        assertThat(p).contains("Redes que ya tiene conectadas: INSTAGRAM, FACEBOOK");
        assertThat(p).contains("Tacos El Güero");
        assertThat(p).contains("no preguntes nada de esto");
    }

    @Test
    @DisplayName("lo que falta se le dice, pidiéndole que lo proponga en vez de preguntarlo en seco")
    void loQueFaltaSePropone() {
        contesta("{\"mensaje\":\"va\",\"opciones\":[\"Sí\"],\"ficha\":{},\"veredicto\":\"VA\"}");

        asistente.hablar("quiero algo", FichaDeImagen.vacia(), List.of(), MARCA, null, List.of());

        assertThat(ultimoPrompt())
                .contains("TODAVIA FALTA")
                .contains("Proponle tu una respuesta en vez de preguntar en seco");
    }

    @Test
    @DisplayName("con la ficha completa se le pide que resuma y confirme antes de gastar")
    void avisaAntesDeCrear() {
        contesta("{\"mensaje\":\"va\",\"opciones\":[\"Sí\"],\"ficha\":{},\"veredicto\":\"VA\"}");
        FichaDeImagen lista = FichaDeImagen.vacia()
                .con(new FichaDeImagen("2x1 en tacos", "PUBLICACION", null, null, null, null, null, null, null));

        asistente.hablar("ya", lista, List.of(), MARCA, null, List.of());

        assertThat(ultimoPrompt())
                .contains("YA NO FALTA NADA IMPRESCINDIBLE")
                .contains("pregunta si la creas");
    }

    @Test
    @DisplayName("solo se recuerdan los últimos turnos: un hilo largo no se vuelve carísimo")
    void memoriaAcotada() {
        contesta("{\"mensaje\":\"va\",\"opciones\":[\"Sí\"],\"ficha\":{},\"veredicto\":\"VA\"}");
        List<AsistenteDeImagenes.Turno> muchos = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            muchos.add(new AsistenteDeImagenes.Turno(i % 2 == 0, "turno numero " + i));
        }

        asistente.hablar("sigue", FichaDeImagen.vacia(), muchos, MARCA, null, List.of());

        String p = ultimoPrompt();
        assertThat(p).contains("turno numero 39");
        assertThat(p).doesNotContain("turno numero 5");
    }

    // ------------------------------------------------- el filtro

    @Test
    @DisplayName("lo que no se puede hacer se descarta, y se dice por qué")
    void descartaYExplica() {
        contesta("""
                {"mensaje":"Eso no lo puedo hacer, pero sí puedo hacerte una con tus tacos.",
                 "opciones":["Va, con mis tacos","Mejor otra cosa"],
                 "ficha":{}, "listo":true,
                 "veredicto":"DESCARTADA","motivo":"Es el logo de otra marca."}
                """);

        AsistenteDeImagenes.Respuesta r = asistente.hablar(
                "ponle el logo de Coca-Cola", FichaDeImagen.vacia(), List.of(), MARCA, null, List.of());

        assertThat(r.veredicto()).isEqualTo(RevisorDeMarca.Veredicto.DESCARTADA);
        assertThat(r.motivo()).contains("otra marca");
        assertThat(r.sePuedeSeguir()).isFalse();
        // Aunque el modelo dijera "listo", lo descartado nunca se crea.
        assertThat(r.listo()).isFalse();
    }

    @Test
    @DisplayName("si dice que no sin decir por qué, se pone un porqué: nadie se queda a ciegas")
    void siempreHayMotivo() {
        contesta("{\"mensaje\":\"no\",\"opciones\":[],\"ficha\":{},\"veredicto\":\"OBSERVACION\"}");

        AsistenteDeImagenes.Respuesta r = asistente.hablar(
                "sale un niño", FichaDeImagen.vacia(), List.of(), MARCA, null, List.of());

        assertThat(r.motivo()).isNotBlank();
        // OBSERVACION no bloquea: decide el dueño.
        assertThat(r.sePuedeSeguir()).isTrue();
    }

    @Test
    @DisplayName("un veredicto raro se trata como que sí se puede: bloquear de más ahuyenta")
    void veredictoRaro() {
        contesta("{\"mensaje\":\"va\",\"opciones\":[\"Sí\"],\"ficha\":{},\"veredicto\":\"QUIZA\"}");

        AsistenteDeImagenes.Respuesta r = asistente.hablar(
                "tacos", FichaDeImagen.vacia(), List.of(), MARCA, null, List.of());

        assertThat(r.veredicto()).isEqualTo(RevisorDeMarca.Veredicto.VA);
    }

    // ------------------------------------------------- lo que contesta

    @Test
    @DisplayName("la ficha del modelo se funde con la que ya había")
    void fusionaLaFicha() {
        contesta("""
                {"mensaje":"Lo hago para el muro?","opciones":["Sí, para el muro","Mejor historia"],
                 "ficha":{"queSeAnuncia":"2x1 en tacos","cuando":"viernes"},"veredicto":"VA"}
                """);
        FichaDeImagen antes = FichaDeImagen.vacia()
                .con(new FichaDeImagen(null, "PUBLICACION", null, null, null, null, null, null, null));

        AsistenteDeImagenes.Respuesta r = asistente.hablar(
                "el 2x1 del viernes", antes, List.of(), MARCA, null, List.of());

        assertThat(r.ficha().queSeAnuncia()).isEqualTo("2x1 en tacos");
        assertThat(r.ficha().cuando()).isEqualTo("viernes");
        assertThat(r.ficha().formato()).isEqualTo("PUBLICACION");
        assertThat(r.opciones()).containsExactly("Sí, para el muro", "Mejor historia");
    }

    @Test
    @DisplayName("no se da por listo si todavía falta lo imprescindible")
    void noSeDaPorListoSiFalta() {
        contesta("{\"mensaje\":\"la creo\",\"opciones\":[\"Sí\"],\"ficha\":{},\"listo\":true,\"veredicto\":\"VA\"}");

        AsistenteDeImagenes.Respuesta r = asistente.hablar(
                "hazla", FichaDeImagen.vacia(), List.of(), MARCA, null, List.of());

        assertThat(r.listo()).isFalse();
    }

    @Test
    @DisplayName("más de cuatro opciones se recortan: una fila de botones no es un menú")
    void topeDeOpciones() {
        contesta("""
                {"mensaje":"elige","opciones":["a","b","c","d","e","f"],"ficha":{},"veredicto":"VA"}
                """);

        AsistenteDeImagenes.Respuesta r = asistente.hablar(
                "x", FichaDeImagen.vacia(), List.of(), MARCA, null, List.of());

        assertThat(r.opciones()).hasSize(4);
    }

    @Test
    @DisplayName("si el modelo contesta basura, la pantalla no se queda muda")
    void respuestaIlegible() {
        contesta("esto no es json");

        AsistenteDeImagenes.Respuesta r = asistente.hablar(
                "tacos", FichaDeImagen.vacia(), List.of(), MARCA, null, List.of());

        assertThat(r.mensaje()).isNotBlank();
        assertThat(r.opciones()).isNotEmpty();
        assertThat(r.listo()).isFalse();
    }

    @Test
    @DisplayName("si la IA se cae, se contesta algo y no se pierde la ficha")
    void laIaSeCae() {
        when(client.completeJson(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("sin red"));
        FichaDeImagen antes = FichaDeImagen.vacia()
                .con(new FichaDeImagen("2x1 en tacos", "PUBLICACION", null, null, null, null, null, null, null));

        AsistenteDeImagenes.Respuesta r = asistente.hablar("x", antes, List.of(), MARCA, null, List.of());

        assertThat(r.mensaje()).isNotBlank();
        assertThat(r.ficha().queSeAnuncia()).isEqualTo("2x1 en tacos");
    }
}
