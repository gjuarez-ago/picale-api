package com.metricol.api.models.request;

import com.metricol.api.enums.ObjetivoRedes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Un workspace nuevo para el mismo usuario: normalmente, un cliente más.
 *
 * <p>Un espacio es un negocio, y no basta con su nombre: la IA escribe con el
 * giro, la ciudad, lo que hace y lo que busca. Son los mismos datos que se
 * piden al registrarse (ver {@code RegisterRequest}), y todos son opcionales:
 * quien da de alta a un cliente a las prisas puede completarlos después desde
 * el perfil del espacio.
 */
@Getter
@Setter
public class WorkspaceCreateRequest {

    @NotBlank
    @Size(min = 2, max = 120)
    private String name;

    @Size(max = 120)
    private String giro;

    @Size(max = 120)
    private String ciudad;

    @Size(max = 500)
    private String descripcion;

    private ObjetivoRedes objetivo;
}
