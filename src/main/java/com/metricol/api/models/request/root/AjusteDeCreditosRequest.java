package com.metricol.api.models.request.root;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Sumar (o quitar, en negativo) créditos de imagen a un espacio. */
@Getter
@Setter
public class AjusteDeCreditosRequest {

    @Min(-10000)
    @Max(10000)
    private int delta;

    /** Por qué, para el historial. Opcional. */
    @Size(max = 60)
    private String motivo;

    /**
     * La identidad de ESTE ajuste, puesta por el cliente (un UUID por vez que se
     * abre el formulario). Un reintento de la misma petición trae la misma y no
     * suma dos veces. Opcional: sin ella el servidor inventa una y el reintento
     * no se detecta.
     */
    @Size(max = 36)
    private String referencia;
}
