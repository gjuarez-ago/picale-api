package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.ReelRenderStatus;

public record ReelRenderResponse(
        UUID id,
        ReelRenderStatus status,
        List<UUID> sourceAssetIds,
        int durationSeconds,
        String title,
        String recipeVersion,
        int attempts,
        int maxAttempts,
        String error,
        UUID resultAssetId,
        String resultUrl,
        String thumbnailUrl,
        boolean approved,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime finishedAt) {
}
