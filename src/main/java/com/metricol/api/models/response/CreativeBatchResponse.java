package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.CreativeBatchStatus;

public record CreativeBatchResponse(UUID id, List<UUID> sourceAssetIds, String brief,
        CreativeBatchStatus status, int attempts, int maxAttempts, String error,
        List<CreativeProposalResponse> proposals, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
