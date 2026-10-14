package com.metricol.api.service.creative;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.metricol.api.enums.CreativePieceType;

public record CreativePlan(String recipeVersion, List<Piece> pieces) {
    public record Piece(CreativePieceType type, List<UUID> assetIds, String objective, String title,
            Map<String, String> captions, List<String> networks, Integer durationSeconds, String reason) {
    }
}
