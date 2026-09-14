package com.metricol.api.models.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Retocar un texto ya escrito, para una sola red. */
@Getter
@Setter
public class AjusteRequest {

    /** El texto tal como está ahora en la pantalla. */
    @NotBlank
    private String texto;

    /** La red, para respetar su límite y su estilo. */
    @NotBlank
    private String platform;

    /** CORTO, VENDEDOR o PROFESIONAL. */
    @NotBlank
    private String ajuste;
}
