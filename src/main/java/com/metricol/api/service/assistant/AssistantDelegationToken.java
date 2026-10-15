package com.metricol.api.service.assistant;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.security.JwtService;

/** Emite una identidad corta y ligada al hilo para que la API invoque su MCP. */
@Component
public class AssistantDelegationToken {

    static final String PREFIX = "picale-assistant.v1";
    static final Duration LIFETIME = Duration.ofMinutes(5);

    private final JwtService jwt;
    private final ObjectMapper json;
    private final byte[] key;

    public AssistantDelegationToken(JwtService jwt, ObjectMapper json,
            @Value("${app.assistant.delegation-key:}") String key) {
        this.jwt = jwt;
        this.json = json;
        this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(User user, UUID threadId, UUID runId, java.util.Set<Permission> permissions) {
        if (key.length < 32) {
            throw new IllegalStateException("Falta configurar ASSISTANT_DELEGATION_KEY con al menos 32 caracteres.");
        }
        UUID workspaceId = user.getWorkspace().getId();
        Map<String, Object> apiClaims = new LinkedHashMap<>();
        apiClaims.put("assistant", true);
        apiClaims.put("workspaceId", workspaceId.toString());
        apiClaims.put("assistantThreadId", threadId.toString());
        apiClaims.put("assistantRunId", runId.toString());
        String apiToken = jwt.generateToken(apiClaims, user, LIFETIME);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tp", apiToken);
        payload.put("email", user.getEmail());
        payload.put("userId", user.getId().toString());
        payload.put("workspaceId", workspaceId.toString());
        payload.put("threadId", threadId.toString());
        payload.put("runId", runId.toString());
        payload.put("permissions", permissions.stream().map(Enum::name).sorted().toList());
        payload.put("exp", Instant.now().plus(LIFETIME).getEpochSecond());
        try {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(payload));
            return PREFIX + "." + encoded + "." + sign(encoded);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo crear la delegacion del asistente.", e);
        }
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((PREFIX + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }
}
