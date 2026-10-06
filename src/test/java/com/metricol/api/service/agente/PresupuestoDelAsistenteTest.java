package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Lo que la pantalla lee del presupuesto (antes faltaban "restantes" y "acciones", 6 oct 2026). */
class PresupuestoDelAsistenteTest {

    @Test
    @DisplayName("el presupuesto viaja con lo que queda y para cuántas mejoras o diseños alcanza")
    void json() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new PresupuestoDelAsistente.Estado(50, 10, 5));
        assertThat(json).contains("\"restantes\":40").contains("\"acciones\":8").contains("\"mensual\":50");
    }
}
