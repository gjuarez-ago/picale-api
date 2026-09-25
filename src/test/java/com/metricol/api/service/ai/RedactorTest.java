package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.ObjetivoRedes;
import com.metricol.api.enums.Platform;

/**
 * Lo que se prueba aquí es qué pasa cuando el modelo contesta MAL.
 *
 * <p>Que conteste bien no hace falta probarlo: si devuelve el JSON completo,
 * leerlo es trivial. El riesgo está en lo otro — que se salte una red, que
 * devuelva basura, que se pase del límite de caracteres. Y ese riesgo no es
 * teórico: un modelo no cuenta caracteres de forma fiable, y una red sin texto
 * se publica vacía.
 */
class RedactorTest {

    private OpenAiClient client;
    private Redactor redactor;

    @BeforeEach
    void preparar() {
        client = mock(OpenAiClient.class);
        redactor = new Redactor(client);
    }

    @Test
    @DisplayName("cada red se queda con su texto")
    void repartAElCadaTexto() {
        when(client.completeJson(any(), anyString(), anyString())).thenReturn("""
                {"titulo": "2x1 en tacos al pastor hoy",
                 "guion": "Anunciar el 2x1 de hoy",
                 "textos": {"INSTAGRAM": "Hoy hay 2x1 🌮", "YOUTUBE": "2x1 hoy. Corre."}}
                """);

        Redactor.Borrador borrador = redactor.redactar(
                "anuncia el 2x1", List.of("tacos al pastor"),
                List.of(Platform.INSTAGRAM, Platform.YOUTUBE), Redactor.Negocio.DESCONOCIDO);

        assertThat(borrador.titulo()).isEqualTo("2x1 en tacos al pastor hoy");
        assertThat(borrador.guion()).isEqualTo("Anunciar el 2x1 de hoy");
        assertThat(borrador.textos().get(Platform.INSTAGRAM)).isEqualTo("Hoy hay 2x1 🌮");
        assertThat(borrador.textos().get(Platform.YOUTUBE)).isEqualTo("2x1 hoy. Corre.");
    }

    @Test
    @DisplayName("si falta una red, se rellena con el guion en vez de dejarla vacia")
    void ningunaRedSeQuedaSinTexto() {
        // Una red sin texto no es un hueco en la pantalla: es una publicacion
        // vacia en el perfil de alguien.
        when(client.completeJson(any(), anyString(), anyString())).thenReturn("""
                {"guion": "Anunciar el 2x1", "textos": {"INSTAGRAM": "Hoy hay 2x1"}}
                """);

        Redactor.Borrador borrador = redactor.redactar(
                "anuncia el 2x1", List.of(),
                List.of(Platform.INSTAGRAM, Platform.YOUTUBE, Platform.LINKEDIN),
                Redactor.Negocio.DESCONOCIDO);

        assertThat(borrador.textos()).containsOnlyKeys(
                Platform.INSTAGRAM, Platform.YOUTUBE, Platform.LINKEDIN);
        assertThat(borrador.textos().values()).noneMatch(String::isBlank);
    }

    @Test
    @DisplayName("un texto que se pasa del limite se recorta antes de salir")
    void seRespetaElLimiteDeCaracteres() {
        // YouTube corta en 100. Que el prompt lo pida no basta: un modelo no cuenta
        // caracteres bien, y el que llega pasado lo rechaza la red.
        String largo = "a".repeat(500);
        when(client.completeJson(any(), anyString(), anyString()))
                .thenReturn("{\"guion\": \"x\", \"textos\": {\"YOUTUBE\": \"" + largo + "\"}}");

        Redactor.Borrador borrador = redactor.redactar(
                "algo", List.of(), List.of(Platform.YOUTUBE), Redactor.Negocio.DESCONOCIDO);

        assertThat(borrador.textos().get(Platform.YOUTUBE).length())
                .isLessThanOrEqualTo(EspecTexto.de(Platform.YOUTUBE).maxCaracteres());
    }

    @Test
    @DisplayName("si la IA devuelve basura no revienta: devuelve algo publicable")
    void laBasuraNoTumbaNada() {
        when(client.completeJson(any(), anyString(), anyString())).thenReturn("esto no es JSON");

        Redactor.Borrador borrador = redactor.redactar(
                "algo", List.of(), List.of(Platform.INSTAGRAM), Redactor.Negocio.DESCONOCIDO);

        assertThat(borrador.textos()).containsKey(Platform.INSTAGRAM);
    }

    @Test
    @DisplayName("recortar no parte una palabra por la mitad")
    void elRecorteRespetaLasPalabras() {
        EspecTexto yt = EspecTexto.de(Platform.YOUTUBE);
        String texto = "palabra ".repeat(200).strip();

        String recortado = yt.recortar(texto);

        assertThat(recortado.length()).isLessThanOrEqualTo(yt.maxCaracteres());
        assertThat(recortado).endsWith("…");
        assertThat(recortado).doesNotContain("palab…");
    }

    // ---------- Retoques (los chips de estilo) ----------

    @Test
    @DisplayName("al retocar, el limite va como regla dura y con los hashtags de esa red")
    void elRetoqueLlevaElLimite() {
        when(client.complete(any(), anyString(), anyString())).thenReturn("2x1 hoy #tacos");

        redactor.ajustar("2x1 hoy", Platform.TIKTOK, Ajuste.HASHTAGS);

        ArgumentCaptor<String> sistema = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(client).complete(eq(AiOperacion.AJUSTAR), sistema.capture(), prompt.capture());
        assertThat(sistema.getValue()).contains("limite DURO");
        assertThat(prompt.getValue())
                .contains("Limite duro: 300 caracteres")
                .contains("Hashtags: como maximo 2")
                .contains("Texto actual (7 caracteres)")
                .contains(Ajuste.HASHTAGS.getInstruccion());
    }

    @Test
    @DisplayName("si el retoque cabe, sale a la primera")
    void loQueCabeSaleALaPrimera() {
        when(client.complete(any(), anyString(), anyString())).thenReturn("2x1 hoy en tacos. Ven.");

        String resultado = redactor.ajustar("2x1 hoy", Platform.TIKTOK, Ajuste.VENDEDOR);

        assertThat(resultado).isEqualTo("2x1 hoy en tacos. Ven.");
        verify(client, times(1)).complete(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("si el retoque se pasa, se pide acortarlo en vez de cortarlo con puntos suspensivos")
    void loQueSePasaSeAcortaEnVezDeCortarse() {
        // El caso que motivo esto: "Mas largo" en TikTok. Recortado, se
        // perdian justo los hashtags del final.
        // Mas de 300, que es el caption de TikTok desde que el titulo va aparte.
        String largo = "Hoy tenemos dos por uno en todos los tacos al pastor hasta las seis"
                + " de la tarde, ven con tus amigos, trae a la familia, hay lugar para todos,"
                + " musica en vivo, promociones en bebidas y un ambiente increible para pasar"
                + " la tarde con quien mas quieres, y estacionamiento gratis para quien"
                + " llegue antes de las cinco, te esperamos #tacos #2x1";
        assertThat(largo.length()).isGreaterThan(300);
        String corto = "2x1 en tacos al pastor hasta las 6 #tacos #2x1";
        when(client.complete(any(), anyString(), anyString())).thenReturn(largo, corto);

        String resultado = redactor.ajustar("2x1 en tacos", Platform.TIKTOK, Ajuste.LARGO);

        assertThat(resultado).isEqualTo(corto);
        ArgumentCaptor<AiOperacion> operacion = ArgumentCaptor.forClass(AiOperacion.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(operacion.capture(), anyString(), prompt.capture());
        assertThat(prompt.getAllValues().get(1)).contains("caracteres de mas");
        // Cada vuelta se anota aparte en el gasto: la segunda existe solo
        // porque la primera se paso, y conviene poder verla sola.
        assertThat(operacion.getAllValues()).containsExactly(AiOperacion.AJUSTAR, AiOperacion.ACORTAR);
    }

    @Test
    @DisplayName("si tampoco cabe a la segunda, se recorta: nunca sale pasado del limite")
    void aLaSegundaSeRecorta() {
        String largo = "palabra ".repeat(40).strip();
        when(client.complete(any(), anyString(), anyString())).thenReturn(largo, largo);

        String resultado = redactor.ajustar("algo", Platform.TIKTOK, Ajuste.LARGO);

        assertThat(resultado.length()).isLessThanOrEqualTo(300);
        verify(client, times(2)).complete(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("si falla la segunda vuelta, se queda con el retoque recortado")
    void siFallaAcortarSeRecorta() {
        String largo = "palabra ".repeat(40).strip();
        when(client.complete(any(), anyString(), anyString()))
                .thenReturn(largo)
                .thenThrow(new IllegalStateException("sin red"));

        String resultado = redactor.ajustar("algo", Platform.TIKTOK, Ajuste.LARGO);

        assertThat(resultado.length()).isLessThanOrEqualTo(300);
        assertThat(resultado).startsWith("palabra");
    }

    @Test
    @DisplayName("Lo que se sabe del negocio llega al prompt, y con el limite de no inventar")
    void elNegocioLlegaAlPrompt() {
        when(client.completeJson(any(), anyString(), anyString()))
                .thenReturn("{\"guion\": \"x\", \"textos\": {\"INSTAGRAM\": \"hola\"}}");

        redactor.redactar(
                "anuncia el 2x1",
                List.of(),
                List.of(Platform.INSTAGRAM),
                new Redactor.Negocio(
                        "Tacos El Guero",
                        "Restaurante",
                        "Merida",
                        "Vendemos tacos al pastor",
                        ObjetivoRedes.MAS_CLIENTES));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(client).completeJson(any(), anyString(), prompt.capture());

        assertThat(prompt.getValue())
                .contains("Giro: Restaurante")
                .contains("Ciudad: Merida")
                .contains("Vendemos tacos al pastor")
                // El objetivo entra traducido a instruccion, no como codigo:
                // "MAS_CLIENTES" no le dice nada al modelo.
                .contains("atraer clientes nuevos")
                // Sin este limite, dos datos sueltos le bastan para inventar
                // precios y horarios que acaban publicados.
                .contains("NO inventes nada");
    }

    @Test
    @DisplayName("Sin datos del negocio no se cuela una cabecera vacia")
    void sinNegocioNoHayCabecera() {
        when(client.completeJson(any(), anyString(), anyString()))
                .thenReturn("{\"guion\": \"x\", \"textos\": {\"INSTAGRAM\": \"hola\"}}");

        redactor.redactar(
                "algo", List.of(), List.of(Platform.INSTAGRAM), Redactor.Negocio.DESCONOCIDO);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(client).completeJson(any(), anyString(), prompt.capture());

        assertThat(prompt.getValue()).doesNotContain("El negocio que publica");
    }
}
