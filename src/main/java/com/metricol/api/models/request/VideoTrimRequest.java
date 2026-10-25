package com.metricol.api.models.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** Los dos limites que la persona eligio en el editor de video. */
public record VideoTrimRequest(
        @NotNull @DecimalMin("0.0") Double startSeconds,
        @NotNull @DecimalMin("0.1") Double endSeconds) {
}
