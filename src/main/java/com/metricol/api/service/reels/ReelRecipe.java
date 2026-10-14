package com.metricol.api.service.reels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Receta determinista: sirve de trazabilidad y llave de idempotencia. */
public record ReelRecipe(String version, UUID workspaceId, List<UUID> assetIds,
        int durationSeconds, String title) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public ReelRecipe {
        assetIds = List.copyOf(assetIds);
        title = title == null || title.isBlank() ? null : title.strip();
    }

    public String json() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar la receta del reel.", ex);
        }
    }

    public String hash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo calcular la identidad del reel.", ex);
        }
    }
}
