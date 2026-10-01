package com.metricol.api.models.request.root;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * La confirmación de un borrado definitivo: el nombre de la organización o del
 * espacio, o el correo de la persona, escrito a mano. Un clic de más no basta.
 */
@Getter
@Setter
public class EliminarRequest {

    @NotBlank
    private String confirmacion;
}
