package com.metricol.api.models.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CaptionSuggestionRequest {

    /** Breve descripción de lo que se quiere publicar, escrita por el usuario. */
    @NotBlank
    private String brief;
}
