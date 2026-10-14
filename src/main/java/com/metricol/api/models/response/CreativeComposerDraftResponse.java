package com.metricol.api.models.response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreativeComposerDraftResponse(UUID proposalId, String format, String title,
        String caption, Map<String, String> captionsPorRed, List<String> mediaUrls,
        Integer videoDurationSeconds, List<String> recommendedNetworks) {
}
