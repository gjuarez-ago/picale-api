package com.metricol.api.models.request.root;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Dejar la licencia de un espacio como diga aquí.
 *
 * <p>{@code status} es uno de TRIALING, ACTIVE, PAST_DUE o ENDED, y
 * {@code vigenteHasta} es la fecha que manda para ese estado: fin de la
 * prueba, fin del periodo pagado o fin de la gracia. Con ENDED no hace falta.
 */
@Getter
@Setter
public class LicenciaRequest {

    @NotBlank
    private String status;

    private LocalDateTime vigenteHasta;

    /** Si Stripe no debe renovarla al terminar el periodo. Nulo = no cambiar. */
    private Boolean cancelAtPeriodEnd;
}
