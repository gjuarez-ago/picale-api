package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Permission;
import com.sun.net.httpserver.HttpServer;

class AssistantMcpClientTest {

    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer delegacion");
            String response = body.contains("tools/list")
                    ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"panel\"}]}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}";
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void descubreYLlamaHerramientasConLaMismaDelegacion() {
        AssistantDelegationToken delegations = mock(AssistantDelegationToken.class);
        when(delegations.issue(any(), any(), any(), any())).thenReturn("delegacion");
        ObjectMapper json = new ObjectMapper();
        AssistantMcpClient client = new AssistantMcpClient(delegations, json, endpoint, HttpClient.newHttpClient());
        User user = User.builder().id(UUID.randomUUID()).email("ana@picale.test")
                .workspace(Workspace.builder().id(UUID.randomUUID()).build()).build();

        AssistantMcpClient.Session session = client.open(user, UUID.randomUUID(), UUID.randomUUID(),
                EnumSet.of(Permission.POST_CREATE));

        assertThat(session.tools().get(0).path("name").asText()).isEqualTo("panel");
        assertThat(session.call("panel", json.createObjectNode()).at("/content/0/text").asText()).isEqualTo("ok");
    }
}
