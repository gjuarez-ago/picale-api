package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Que el agente no se gaste los créditos del mes en el primer lote. */
class RitmoDeCreditosTest {

    private static final LocalDate DIA_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate FIN = LocalDate.of(2026, 10, 31);

    @Test
    @DisplayName("cinco créditos el día 1: uno de reserva y los otros cuatro repartidos, uno por semana")
    void reparte() {
        assertThat(RitmoDeCreditos.estaSemana(5, FIN, DIA_1, 0)).isEqualTo(1);
    }

    @Test
    @DisplayName("si ya usó el de esta semana, no hay más hasta la siguiente")
    void yaUsado() {
        assertThat(RitmoDeCreditos.estaSemana(4, FIN, DIA_1, 1)).isZero();
    }

    @Test
    @DisplayName("con dos créditos y un mes por delante sí diseña: redondea hacia arriba")
    void pocosCreditos() {
        assertThat(RitmoDeCreditos.estaSemana(2, FIN, DIA_1, 0)).isEqualTo(1);
    }

    @Test
    @DisplayName("el último crédito nunca se gasta: es para la persona")
    void reserva() {
        assertThat(RitmoDeCreditos.estaSemana(1, FIN, DIA_1, 0)).isZero();
    }

    @Test
    @DisplayName("en la última semana se puede usar todo lo que sobra")
    void ultimaSemana() {
        assertThat(RitmoDeCreditos.estaSemana(5, FIN, LocalDate.of(2026, 10, 27), 0)).isEqualTo(4);
    }

    @Test
    @DisplayName("sin cobros no hay ritmo que cuidar")
    void sinCobros() {
        assertThat(RitmoDeCreditos.estaSemana(Integer.MAX_VALUE, null, DIA_1, 9)).isEqualTo(Integer.MAX_VALUE);
    }
}
