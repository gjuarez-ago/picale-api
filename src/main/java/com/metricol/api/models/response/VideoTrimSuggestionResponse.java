package com.metricol.api.models.response;

/** Limites iniciales del editor. Son una sugerencia: nunca ejecutan el recorte. */
public record VideoTrimSuggestionResponse(
        double durationSeconds,
        double startSeconds,
        double endSeconds,
        boolean suggested) {
}
