package com.metricol.api.models.request.root;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** Encender o apagar el asistente en un espacio de trabajo. */
@Getter
@Setter
public class ModuloAsistenteRequest {

    @NotNull
    private Boolean valor;
}
