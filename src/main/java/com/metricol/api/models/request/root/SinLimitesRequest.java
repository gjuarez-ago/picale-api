package com.metricol.api.models.request.root;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** Encender o apagar «sin límites» (exenta de pago, sin vigencia ni cupos) en una organización. */
@Getter
@Setter
public class SinLimitesRequest {

    @NotNull
    private Boolean valor;
}
