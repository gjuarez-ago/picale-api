package com.metricol.api.service.assistant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;

/** Cliente interno del catalogo MCP; no conoce ni replica herramientas concretas. */
@Component
public class AssistantMcpClient {

    private final AssistantDelegationToken delegations;
    private final ObjectMapper json;
    private final URI endpoint;
    private final HttpClient http;
    private final AtomicLong ids = new AtomicLong();

    @Autowired
    public AssistantMcpClient(AssistantDelegationToken delegations, ObjectMapper json,
            @Value("${app.assistant.mcp-url}") String endpoint) {
        this(delegations, json, endpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    AssistantMcpClient(AssistantDelegationToken delegations, ObjectMapper json, String endpoint, HttpClient http) {
        this.delegations = delegations;
        this.json = json;
        this.endpoint = URI.create(endpoint);
        this.http = http;
    }

    public Session open(User user, UUID threadId, UUID runId, Set<Permission> permissions) {
        return new Session(delegations.issue(user, threadId, runId, permissions));
    }

    public final class Session {
        private final String token;

        private Session(String token) {
            this.token = token;
        }

        public JsonNode tools() {
            ObjectNode request = envelope("tools/list");
            request.set("params", json.createObjectNode());
            return send(request).path("tools");
        }

        public JsonNode call(String name, JsonNode arguments) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Falta el nombre de la herramienta MCP.");
            }
            ObjectNode params = json.createObjectNode();
            params.put("name", name);
            params.set("arguments", arguments == null ? json.createObjectNode() : arguments);
            ObjectNode request = envelope("tools/call");
            request.set("params", params);
            return send(request);
        }

        private ObjectNode envelope(String method) {
            ObjectNode request = json.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", ids.incrementAndGet());
            request.put("method", method);
            return request;
        }

        private JsonNode send(ObjectNode body) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(95))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response = http.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonNode envelope = json.readTree(response.body());
                if (response.statusCode() / 100 != 2 || envelope.has("error")) {
                    String message = envelope.at("/error/message").asText("El MCP no pudo completar la solicitud.");
                    throw new McpException(response.statusCode(), message);
                }
                JsonNode result = envelope.get("result");
                if (result == null) {
                    throw new McpException(502, "El MCP devolvio una respuesta incompleta.");
                }
                return result;
            } catch (IOException e) {
                throw new McpException(503, "El MCP no respondio. Intenta de nuevo en un momento.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpException(503, "La llamada al MCP se interrumpio.", e);
            }
        }
    }

    public static class McpException extends RuntimeException {
        private final int status;

        McpException(int status, String message) {
            super(message);
            this.status = status;
        }

        McpException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
