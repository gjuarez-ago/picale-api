package com.metricol.api.service.agente;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Cuántos diseños puede hacer el agente esta semana sin gastarse el mes de golpe.
 *
 * <p>Con cinco créditos al mes, "gasta si hay" se los acaba el primer lote y
 * deja sin diseño la promoción que llegue el día 20. El ritmo reparte lo que
 * queda entre las semanas que faltan para que venza, guardando siempre uno
 * para lo que la persona cree a mano.
 *
 * <p>Redondea hacia arriba a propósito: con dos créditos y cuatro semanas, un
 * redondeo hacia abajo daría cero cada semana y el agente no diseñaría nunca.
 */
public final class RitmoDeCreditos {

    /** Créditos que el agente nunca toca: quedan para lo que la persona cree a mano. */
    public static final int RESERVA = 1;

    private RitmoDeCreditos() {
    }

    /**
     * @param disponibles       los créditos que tiene ahora la cuenta;
     *                          {@code Integer.MAX_VALUE} = sin cobros ni límite
     * @param vencen            cuándo vencen los del mes; nulo = fin de mes
     * @param hoy               el día de hoy
     * @param usadosEstaSemana  diseños que el agente ya hizo esta semana
     * @return cuántos más caben esta semana
     */
    public static int estaSemana(int disponibles, LocalDate vencen, LocalDate hoy, int usadosEstaSemana) {
        if (disponibles == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        int gastables = disponibles - RESERVA;
        if (gastables <= 0) {
            return 0;
        }
        LocalDate fin = vencen != null && vencen.isAfter(hoy) ? vencen : hoy.with(TemporalAdjusters.lastDayOfMonth());
        long dias = Math.max(1, ChronoUnit.DAYS.between(hoy, fin) + 1);
        int semanas = (int) Math.max(1, (dias + 6) / 7);
        // Lo de esta semana más lo que ya gastó: el ritmo se calcula sobre lo que había al empezarla.
        int porSemana = (gastables + usadosEstaSemana + semanas - 1) / semanas;
        return Math.max(0, Math.min(gastables, porSemana - usadosEstaSemana));
    }
}
