package com.metricol.api.models.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Contexto mínimo para proponer la descripción editable de un negocio. */
@Getter
@Setter
public class BusinessDescriptionSuggestionRequest {

    @Size(max = 120)
    private String nombre;

    @NotBlank
    @Size(max = 120)
    private String giro;

    @Size(max = 120)
    private String ciudad;

    @Size(max = 500)
    private String descripcionActual;
}
