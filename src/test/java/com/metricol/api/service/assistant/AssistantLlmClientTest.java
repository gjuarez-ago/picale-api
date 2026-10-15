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
}
