package com.metricol.api.service.assistant;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricol.api.config.OpenAiProperties;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.service.ai.AiUsageRecorder;

/** Un paso del bucle de tool calling del asistente. */
@Component
public class AssistantLlmClient {

    public record ToolCall(String id, String name, JsonNode arguments) {
    }

    public record Turn(String content, List<ToolCall> toolCalls, ObjectNode assistantMessage,
            String model, int inputTokens, int outputTokens) {
    }

    private final OpenAiProperties props;
    private final AiUsageRecorder usage;
    private final ObjectMapper json;
    private final RestClient http;

    public AssistantLlmClient(OpenAiProperties props, AiUsageRecorder usage, ObjectMapper json) {
        this.props = props;
        this.usage = usage;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(90));
        this.http = RestClient.builder().baseUrl("https://api.openai.com/v1")
                .requestFactory(factory).build();
    }

    public Turn complete(ArrayNode messages, JsonNode mcpTools) {
        if (!props.isConfigured()) {
            throw new IllegalStateException("Falta configurar OPENAI_API_KEY.");
        }
        ObjectNode body = json.createObjectNode();
        body.put("model", props.getModel());
        body.set("messages", messages);
        body.set("tools", toolsForModel(mcpTools, json));
        body.put("tool_choice", "auto");
        body.put("parallel_tool_calls", false);
        if (reasoningModel(props.getModel()) && props.getReasoningEffort() != null
                && !props.getReasoningEffort().isBlank()) {
            body.put("reasoning_effort", props.getReasoningEffort().strip());
        }

        JsonNode response = http.post().uri("/chat/completions")
                .header("Authorization", "Bearer " + props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JsonNode.class);
        if (response == null || !response.at("/choices/0/message").isObject()) {
            throw new IllegalStateException("OpenAI no devolvio una respuesta util para el asistente.");
        }
        ObjectNode message = (ObjectNode) response.at("/choices/0/message").deepCopy();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            if (!"function".equals(call.path("type").asText())) continue;
            String rawArguments = call.at("/function/arguments").asText("{}");
            try {
                calls.add(new ToolCall(call.path("id").asText(), call.at("/function/name").asText(),
                        json.readTree(rawArguments)));
            } catch (Exception e) {
                throw new IllegalStateException("El modelo devolvio argumentos de herramienta invalidos.", e);
            }
        }
        String model = response.path("model").asText(props.getModel());
        int input = response.at("/usage/prompt_tokens").asInt(0);
        int output = response.at("/usage/completion_tokens").asInt(0);
        usage.registrar(AiOperacion.ASISTENTE_OPERATIVO, model, input, output);
        return new Turn(message.path("content").isTextual() ? message.path("content").asText().strip() : "",
                List.copyOf(calls), message, model, input, output);
    }

    /** Convierte el catalogo MCP sin copiar sus nombres, descripciones ni esquemas. */
    static ArrayNode toolsForModel(JsonNode mcpTools, ObjectMapper json) {
        ArrayNode result = json.createArrayNode();
        if (mcpTools == null || !mcpTools.isArray()) return result;
        for (JsonNode tool : mcpTools) {
            if (!tool.path("name").isTextual() || !tool.path("inputSchema").isObject()) continue;
            ObjectNode function = json.createObjectNode();
            function.put("name", tool.path("name").asText());
            function.put("description", tool.path("description").asText(""));
            function.set("parameters", tool.path("inputSchema").deepCopy());
            function.put("strict", false);
            ObjectNode wrapper = result.addObject();
            wrapper.put("type", "function");
            wrapper.set("function", function);
        }
        return result;
    }

    private static boolean reasoningModel(String model) {
        if (model == null) return false;
        String value = model.toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("gpt-5") || value.startsWith("o1") || value.startsWith("o3") || value.startsWith("o4");
    }
}
