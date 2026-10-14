package com.metricol.api.models.request;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReelCreateRequest {
    @NotNull
    @Size(min = 4, max = 8)
    private List<UUID> assetIds = new ArrayList<>();

    /** Nulo usa la duración editorial configurada. */
    private Integer durationSeconds;

    @Size(max = 160)
    private String title;
}
