package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.metricol.api.enums.CreativePieceStatus;
import com.metricol.api.enums.CreativePieceType;
import com.metricol.api.enums.CreativeApprovalStatus;

public record CreativeProposalResponse(UUID id, CreativePieceType type, List<UUID> sourceAssetIds,
        String objective, String title, Map<String, String> captions, List<String> networks,
        Integer durationSeconds, String recipeVersion, String selectionReason,
        CreativePieceStatus generationStatus, String generationError, UUID reelJobId,
        List<String> previewUrls, String previewThumbnailUrl,
        CreativeApprovalStatus approvalStatus, boolean approved, LocalDateTime approvedAt) {
}
