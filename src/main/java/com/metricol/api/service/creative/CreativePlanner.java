package com.metricol.api.service.creative;

import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.MediaType;

public interface CreativePlanner {
    CreativePlan plan(List<AssetInput> assets, String brief);

    record AssetInput(UUID id, MediaType type, String fileName, String description, String visualAnalysis) {
    }
}
