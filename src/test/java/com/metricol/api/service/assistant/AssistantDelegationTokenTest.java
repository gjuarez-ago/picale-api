package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Permission;
import com.metricol.api.security.JwtService;

class AssistantDelegationTokenTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ligaLaDelegacionAlUsuarioWorkspaceHiloYRun() throws Exception {
        JwtService jwt = mock(JwtService.class);
        when(jwt.generateToken(any(), any(), any())).thenReturn("jwt-corto");
        AssistantDelegationToken tokens = new AssistantDelegationToken(jwt, json,
                "una-llave-de-prueba-con-mas-de-32-caracteres");
        UUID workspaceId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).email("ana@picale.test")
                .workspace(Workspace.builder().id(workspaceId).build()).build();

        String token = tokens.issue(user, threadId, runId, EnumSet.of(Permission.POST_CREATE));
        String[] parts = token.split("\\.");
        Map<String, Object> payload = json.readValue(Base64.getUrlDecoder().decode(parts[2]), new TypeReference<>() {});

        assertThat(parts).hasSize(4);
        assertThat(new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8)).doesNotContain("password");
        assertThat(payload.get("workspaceId")).isEqualTo(workspaceId.toString());
        assertThat(payload.get("threadId")).isEqualTo(threadId.toString());
        assertThat(payload.get("runId")).isEqualTo(runId.toString());
        assertThat(payload.get("tp")).isEqualTo("jwt-corto");
    }

    @Test
    void noEmiteConUnaLlaveDebil() {
        AssistantDelegationToken tokens = new AssistantDelegationToken(mock(JwtService.class), json, "corta");
        User user = User.builder().id(UUID.randomUUID()).email("ana@picale.test")
                .workspace(Workspace.builder().id(UUID.randomUUID()).build()).build();

        assertThatThrownBy(() -> tokens.issue(user, UUID.randomUUID(), UUID.randomUUID(), EnumSet.noneOf(Permission.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ASSISTANT_DELEGATION_KEY");
    }
}
