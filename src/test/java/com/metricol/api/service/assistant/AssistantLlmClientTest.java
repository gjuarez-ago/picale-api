package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class AssistantLlmClientTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void convierteElCatalogoMcpSinDuplicarContratos() throws Exception {
        var mcp = json.readTree("""
                [{"name":"panel","description":"Resumen real", "inputSchema":{"type":"object","properties":{}}}]
                """);

        var tools = AssistantLlmClient.toolsForModel(mcp, json);

        assertThat(tools.at("/0/type").asText()).isEqualTo("function");
        assertThat(tools.at("/0/function/name").asText()).isEqualTo("panel");
        assertThat(tools.at("/0/function/description").asText()).isEqualTo("Resumen real");
        assertThat(tools.at("/0/function/parameters/type").asText()).isEqualTo("object");
        assertThat(tools.at("/0/function/strict").asBoolean()).isFalse();
    }

    /**
     * Un 429 por tokens por minuto es «vuelve en nueve segundos», no un fallo: si se
     * le cobra a la persona, pierde su turno por un limite que no es suyo.
     */
    /** Un servidor de mentira que guarda lo que recibe y responde lo que se le diga. */
    private static final class Falso implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer servidor;
        final java.util.List<String> recibido = new java.util.ArrayList<>();
        final java.util.List<String> rutas = new java.util.ArrayList<>();
        private final java.util.ArrayDeque<String> respuestas = new java.util.ArrayDeque<>();

        Falso(String... cuerpos) throws java.io.IOException {
            respuestas.addAll(java.util.List.of(cuerpos));
            servidor = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/v1", intercambio -> {
                recibido.add(new String(intercambio.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
                rutas.add(intercambio.getRequestURI().getPath());
                byte[] datos = respuestas.poll().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                intercambio.getResponseHeaders().add("Content-Type", "application/json");
                intercambio.sendResponseHeaders(200, datos.length);
                try (var s = intercambio.getResponseBody()) {
                    s.write(datos);
                }
            });
            servidor.start();
        }

        String url() {
            return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1";
        }

        @Override
        public void close() {
            servidor.stop(0);
        }
    }

    private AssistantLlmClient porResponses(Falso falso) {
        var props = new com.metricol.api.config.OpenAiProperties();
        props.setApiKey("sk-prueba");
        props.setModel("gpt-6-luna");
        props.setAssistantApi("responses");
        props.setAssistantReasoningEffort("high");
        return new AssistantLlmClient(props,
                org.mockito.Mockito.mock(com.metricol.api.service.ai.AiUsageRecorder.class), json, falso.url());
    }

    @Test void enviaLaImagenComoEntradaVisualPorResponsesSinRecortarla() throws Exception {
        try (var falso = new Falso("{\"model\":\"gpt-6-luna\",\"output\":[]}")) {
            var result = json.createObjectNode();
            String data = AssistantToolResultTest.photo();
            result.putArray("content").addObject().put("type", "image").put("mimeType", "image/png").put("data", data);
            var history = json.createArrayNode().add(AssistantToolResult.message(json, "call-image", result));
            porResponses(falso).complete(history, null);
            var sent = json.readTree(falso.recibido.get(0));
            assertThat(sent.at("/input/0/type").asText()).isEqualTo("function_call_output");
            assertThat(sent.at("/input/0/output/1/type").asText()).isEqualTo("input_image");
            assertThat(sent.at("/input/0/output/1/image_url").asText()).isEqualTo("data:image/png;base64," + data);
            assertThat(sent.at("/input/0/output/1/detail").asText()).isEqualTo("high");
        }
    }

    @Test void enviaImagenPorChatDespuesDeTodosLosResultadosDeHerramienta() throws Exception {
        try (var falso = new Falso("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Veo la foto\"}}]}")) {
            var props = new com.metricol.api.config.OpenAiProperties();
            props.setApiKey("sk-prueba"); props.setModel("gpt-6-luna"); props.setAssistantApi("chat");
            var client = new AssistantLlmClient(props, org.mockito.Mockito.mock(com.metricol.api.service.ai.AiUsageRecorder.class), json, falso.url());
            var result = json.createObjectNode();
            result.putArray("content").addObject().put("type", "image").put("mimeType", "image/png").put("data", AssistantToolResultTest.photo());
            var history = json.createArrayNode().add(AssistantToolResult.message(json, "call-image", result));
            history.addObject().put("role", "tool").put("tool_call_id", "other").put("content", "Datos de marca");
            client.complete(history, null);
            var sent = json.readTree(falso.recibido.get(0));
            assertThat(sent.at("/messages/1/tool_call_id").asText()).isEqualTo("other");
            assertThat(sent.at("/messages/2/role").asText()).isEqualTo("user");
            assertThat(sent.at("/messages/2/content/2/type").asText()).isEqualTo("image_url");
            assertThat(sent.toString()).doesNotContain(AssistantToolResult.IMAGES);
        }
    }

    @Test
    void pideRazonamientoYHerramientasPlanasPorResponses() throws Exception {
        try (var falso = new Falso("{\"model\":\"gpt-6-luna\",\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"text\":\"Listo.\"}]}],\"usage\":{\"input_tokens\":9,\"output_tokens\":4}}")) {
            var mcp = json.readTree("""
                    [{"name":"panel","description":"Panel","inputSchema":{"type":"object","properties":{}}}]
                    """);

            var turno = porResponses(falso).complete(json.createArrayNode(), mcp);

            assertThat(falso.rutas).containsExactly("/v1/responses");
            var enviado = json.readTree(falso.recibido.get(0));
            assertThat(enviado.at("/reasoning/effort").asText()).isEqualTo("high");
            // Plano: el esquema anidado de chat/completions aqui no vale.
            assertThat(enviado.at("/tools/0/name").asText()).isEqualTo("panel");
            assertThat(enviado.at("/tools/0/function").isMissingNode()).isTrue();
            assertThat(turno.content()).isEqualTo("Listo.");
            assertThat(turno.inputTokens()).isEqualTo(9);
            assertThat(turno.outputTokens()).isEqualTo(4);
        }
    }

    @Test
    void devuelveLaLlamadaConSuCallIdYNoConSuId() throws Exception {
        try (var falso = new Falso("{\"model\":\"gpt-6-luna\",\"output\":["
                + "{\"type\":\"reasoning\",\"summary\":[]},"
                + "{\"type\":\"function_call\",\"id\":\"fc_123\",\"call_id\":\"call_abc\","
                + "\"name\":\"panel\",\"arguments\":\"{\\\"dias\\\":2}\"}],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")) {

            var turno = porResponses(falso).complete(json.createArrayNode(), json.readTree("[]"));

            assertThat(turno.toolCalls()).singleElement().satisfies(c -> {
                assertThat(c.id()).isEqualTo("call_abc");
                assertThat(c.name()).isEqualTo("panel");
                assertThat(c.arguments().path("dias").asInt()).isEqualTo(2);
            });
        }
    }

    @Test
    void devuelveElRazonamientoAlModeloEnLaVueltaSiguiente() throws Exception {
        String conLlamada = "{\"model\":\"gpt-6-luna\",\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]},"
                + "{\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"panel\",\"arguments\":\"{}\"}],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        String conTexto = "{\"model\":\"gpt-6-luna\",\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"text\":\"Hecho.\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

        try (var falso = new Falso(conLlamada, conTexto)) {
            var cliente = porResponses(falso);
            var historial = json.createArrayNode();
            historial.addObject().put("role", "user").put("content", "¿como vamos?");

            var primero = cliente.complete(historial, json.readTree("[]"));
            // El orquestador guarda lo que devolvio y el resultado, igual que siempre.
            historial.add(primero.assistantMessage());
            historial.addObject().put("role", "tool").put("tool_call_id", "call_abc")
                    .put("content", "{\"publicaciones\":0}");

            cliente.complete(historial, json.readTree("[]"));

            var segunda = json.readTree(falso.recibido.get(1)).path("input");
            var tipos = new java.util.ArrayList<String>();
            segunda.forEach(i -> tipos.add(i.has("type") ? i.path("type").asText() : i.path("role").asText()));
            // Sin el razonamiento de vuelta, el modelo olvida por que eligio esa herramienta.
            assertThat(tipos).containsExactly("user", "reasoning", "function_call", "function_call_output");
            assertThat(segunda.get(3).path("call_id").asText()).isEqualTo("call_abc");
        }
    }

    @Test
    void laSegundaVueltaNoReenviaElHistorial() throws Exception {
        String conLlamada = "{\"id\":\"resp_1\",\"model\":\"gpt-6-luna\",\"output\":["
                + "{\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"panel\",\"arguments\":\"{}\"}],"
                + "\"usage\":{\"input_tokens\":900,\"output_tokens\":5}}";
        String conTexto = "{\"id\":\"resp_2\",\"model\":\"gpt-6-luna\",\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"text\":\"Hecho.\"}]}],\"usage\":{\"input_tokens\":40,\"output_tokens\":3}}";

        try (var falso = new Falso(conLlamada, conTexto)) {
            var cliente = porResponses(falso);
            var historial = json.createArrayNode();
            historial.addObject().put("role", "system").put("content", "Un prompt muy largo");
            historial.addObject().put("role", "user").put("content", "¿como vamos?");

            var primero = cliente.complete(historial, json.readTree("[]"));
            historial.add(primero.assistantMessage());
            historial.addObject().put("role", "tool").put("tool_call_id", "call_abc").put("content", "{}");

            cliente.complete(historial, json.readTree("[]"));

            var segunda = json.readTree(falso.recibido.get(1));
            assertThat(segunda.path("previous_response_id").asText()).isEqualTo("resp_1");
            // Solo el resultado de la herramienta: lo de antes ya lo tiene OpenAI.
            assertThat(segunda.path("input")).hasSize(1);
            assertThat(segunda.at("/input/0/type").asText()).isEqualTo("function_call_output");
        }
    }

    /**
     * Reanudar tras «Confirmar y continuar» rearma la llamada en formato de chat/completions.
     * Antes se perdía al traducir a Responses y OpenAI rechazaba el resultado huérfano con un
     * 400, que la persona veía como «no pude conectarme».
     */
    @Test
    void alReanudarTrasConfirmarMandaLaLlamadaAntesQueSuResultado() throws Exception {
        try (var falso = new Falso("{\"id\":\"resp_9\",\"model\":\"gpt-6-luna\",\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"text\":\"Publicado.\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")) {
            var historial = json.createArrayNode();
            historial.addObject().put("role", "user").put("content", "publicalo");
            var pedido = historial.addObject().put("role", "assistant");
            var llamada = pedido.putArray("tool_calls").addObject();
            llamada.put("id", "f5554a83-f050-4890-89f0-d5b0d91ecce2").put("type", "function");
            llamada.putObject("function").put("name", "publicar_ahora").put("arguments", "{\"id\":\"p1\"}");
            historial.addObject().put("role", "tool").put("tool_call_id", "f5554a83-f050-4890-89f0-d5b0d91ecce2")
                    .put("content", "{\"ok\":true}");

            porResponses(falso).complete(historial, json.readTree("[]"));

            var entrada = json.readTree(falso.recibido.get(0)).path("input");
            var tipos = new java.util.ArrayList<String>();
            entrada.forEach(i -> tipos.add(i.has("type") ? i.path("type").asText() : i.path("role").asText()));
            assertThat(tipos).containsExactly("user", "function_call", "function_call_output");
            assertThat(entrada.get(1).path("call_id").asText()).isEqualTo("f5554a83-f050-4890-89f0-d5b0d91ecce2");
            assertThat(entrada.get(1).path("name").asText()).isEqualTo("publicar_ahora");
            assertThat(entrada.get(1).path("arguments").asText()).isEqualTo("{\"id\":\"p1\"}");
        }
    }

    @Test
    void noFallaCuandoLaRespuestaNoTraeRazonamiento() throws Exception {
        try (var falso = new Falso("{\"model\":\"gpt-6-luna\",\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"text\":\"Sin pensarlo mucho.\"}]}],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")) {

            var turno = porResponses(falso).complete(json.createArrayNode(), json.readTree("[]"));

            assertThat(turno.content()).isEqualTo("Sin pensarlo mucho.");
            assertThat(turno.toolCalls()).isEmpty();
        }
    }

    @Test
    void esperaLoQueOpenAiPideYLoVuelveAIntentar() throws Exception {
        java.util.concurrent.atomic.AtomicInteger llamadas = new java.util.concurrent.atomic.AtomicInteger();
        var servidor = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/chat/completions", intercambio -> {
            intercambio.getRequestBody().readAllBytes();
            boolean primera = llamadas.incrementAndGet() == 1;
            String cuerpo = primera
                    ? "{\"error\":{\"message\":\"Rate limit reached for gpt-5-mini on tokens per min (TPM)."
                            + " Please try again in 0.2s\",\"code\":\"rate_limit_exceeded\"}}"
                    : "{\"model\":\"gpt-5-mini\",\"choices\":[{\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"Listo.\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";
            byte[] datos = cuerpo.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(primera ? 429 : 200, datos.length);
            try (var salida = intercambio.getResponseBody()) {
                salida.write(datos);
            }
        });
        servidor.start();

        try {
            var props = new com.metricol.api.config.OpenAiProperties();
            props.setApiKey("sk-prueba");
            props.setModel("gpt-5-mini");
            var cliente = new AssistantLlmClient(props,
                    org.mockito.Mockito.mock(com.metricol.api.service.ai.AiUsageRecorder.class), json,
                    "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1");

            var turno = cliente.complete(json.createArrayNode(), null);

            assertThat(turno.content()).isEqualTo("Listo.");
            assertThat(llamadas.get()).as("tiene que reintentar, no rendirse").isEqualTo(2);
        } finally {
            servidor.stop(0);
        }
    }
}
