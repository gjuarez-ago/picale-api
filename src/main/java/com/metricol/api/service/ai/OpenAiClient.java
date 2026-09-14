package com.metricol.api.service.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.metricol.api.config.OpenAiProperties;
import com.metricol.api.enums.AiOperacion;

/**
 * Cliente delgado sobre la API de Chat Completions de OpenAI.
 *
 * <p>Tres capacidades, y cada una existe por una razón de velocidad:
 *
 * <ul>
 * <li>{@link #complete} — texto suelto, lo de siempre.
 * <li>{@link #completeJson} — obliga al modelo a contestar un JSON válido. Es
 * lo que permite pedir el texto de las CINCO redes en UNA llamada en vez de
 * cinco: una respuesta con una llave por red. Cinco llamadas serían cinco
 * viajes de red y cinco esperas, y lo que se vende aquí es que sea rápido.
 * <li>{@link #describeImages} — le pasa imágenes al modelo. Se mandan por URL
 * y no como bytes: el bucket es público, así que OpenAI la baja por su cuenta
 * y nosotros nos ahorramos descargarla, codificarla y reenviarla.
 * </ul>
 *
 * <p>Cada método pide la {@link AiOperacion}: toda respuesta trae los tokens
 * que OpenAI cobró, y aquí se anotan a nombre del workspace (ver
 * {@link AiUsageRecorder}). Es el único punto por el que pasan todas las
 * llamadas, así que ninguna se queda sin contar.
 */
@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final OpenAiProperties props;
    private final AiUsageRecorder usos;
    private final RestClient restClient;

    public OpenAiClient(OpenAiProperties props, AiUsageRecorder usos) {
        this.props = props;
        this.usos = usos;

        // Con tiempos de espera explicitos: sin ellos, una llamada colgada se
        // queda tomada para siempre, y esta es una peticion que alguien esta
        // esperando mirando la pantalla.
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(10));
        fabrica.setReadTimeout(Duration.ofSeconds(60));

        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .requestFactory(fabrica)
                .build();
    }

    public String complete(AiOperacion operacion, String systemPrompt, String userPrompt) {
        return pedir(operacion, systemPrompt, contenidoDeTexto(userPrompt), false, 0.8);
    }

    /**
     * Igual, pero el modelo devuelve JSON válido o no devuelve nada.
     *
     * <p>La temperatura baja a 0.4: aquí la respuesta tiene que caber en una
     * forma concreta, y la creatividad se quiere dentro del texto, no en la
     * estructura que lo envuelve.
     */
    public String completeJson(AiOperacion operacion, String systemPrompt, String userPrompt) {
        return pedir(operacion, systemPrompt, contenidoDeTexto(userPrompt), true, 0.4);
    }

    /**
     * Le enseña las imágenes al modelo y le pide que cuente qué ve.
     *
     * <p>Las imágenes van con {@code detail: "low"} a propósito. En baja
     * resolución cada imagen cuesta una fracción y tarda mucho menos, y para
     * lo que hace falta aquí —saber que es un plato de comida en una mesa de
     * madera, no leer la letra chica de una etiqueta— sobra.
     */
    public String describeImages(AiOperacion operacion, String systemPrompt, String userPrompt,
            List<String> imageUrls) {
        List<Map<String, Object>> partes = new ArrayList<>();
        partes.add(Map.of("type", "text", "text", userPrompt));
        for (String url : imageUrls) {
            partes.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", url, "detail", "low")));
        }
        return pedir(operacion, systemPrompt, partes, false, 0.3);
    }

    private static List<Map<String, Object>> contenidoDeTexto(String texto) {
        return List.of(Map.of("type", "text", "text", texto));
    }

    @SuppressWarnings("unchecked")
    private String pedir(AiOperacion operacion, String systemPrompt, Object contenidoUsuario,
            boolean json, double temperatura) {
        if (!props.isConfigured()) {
            throw new IllegalStateException("Falta configurar OPENAI_API_KEY.");
        }

        // LinkedHashMap y no Map.of porque el formato solo va cuando toca, y
        // Map.of no admite construir un mapa a medias.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("temperature", temperatura);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", contenidoUsuario)));
        if (json) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        Map<String, Object> respuesta = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        // Antes de mirar si trae texto: una respuesta vacía también se cobra.
        anotar(operacion, respuesta);

        List<Map<String, Object>> choices = respuesta == null
                ? null
                : (List<Map<String, Object>>) respuesta.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("OpenAI no devolvió ninguna sugerencia.");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        Object contenido = message == null ? null : message.get("content");
        if (contenido == null) {
            throw new IllegalStateException("OpenAI devolvió una respuesta vacía.");
        }
        return String.valueOf(contenido).trim();
    }

    /**
     * Anota lo que costó la llamada. Nunca lanza: perder una fila del reporte
     * es mucho menos grave que tirar un texto que la IA ya escribió y que ya
     * se pagó.
     */
    private void anotar(AiOperacion operacion, Map<String, Object> respuesta) {
        if (respuesta == null) {
            return;
        }
        try {
            Uso uso = Uso.de(respuesta);
            // El que contestó y no el que se pidió: OpenAI devuelve la versión
            // concreta ("gpt-4.1-mini-2025-04-14"), que es la que se cobra.
            Object modelo = respuesta.get("model");
            usos.registrar(operacion,
                    modelo instanceof String nombre && !nombre.isBlank() ? nombre : props.getModel(),
                    uso.entrada(), uso.salida());
        } catch (Exception ex) {
            log.warn("No se pudo anotar el uso de IA ({}): {}", operacion, ex.toString());
        }
    }

    /** Los tokens que OpenAI dice haber cobrado, del bloque {@code usage}. */
    record Uso(int entrada, int salida) {

        static Uso de(Map<String, Object> respuesta) {
            Object crudo = respuesta == null ? null : respuesta.get("usage");
            if (!(crudo instanceof Map<?, ?> usage)) {
                return new Uso(0, 0);
            }
            return new Uso(entero(usage.get("prompt_tokens")), entero(usage.get("completion_tokens")));
        }

        private static int entero(Object valor) {
            return valor instanceof Number numero ? numero.intValue() : 0;
        }
    }
}
