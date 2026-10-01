package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.agente.RevisorDeMarca.Veredicto;

/** Cómo se lee lo que contesta la IA al revisar una foto contra la marca. */
class RevisorDeMarcaTest {

    private final RevisorDeMarca revisor = new RevisorDeMarca(null);

    @Test
    @DisplayName("lee el JSON aunque venga envuelto en texto o en un bloque de código")
    void toleraEnvoltorio() throws Exception {
        var r = revisor.interpretar("Claro:\n```json\n{\"veredicto\":\"va\",\"motivo\":\"Es tu producto.\","
                + "\"descripcion\":\"Un depa con vista al mar\",\"idea\":\"Presumir la vista\",\"tipo\":\"producto\"}\n```",
                true);
        assertThat(r.veredicto()).isEqualTo(Veredicto.VA);
        assertThat(r.tipo()).isEqualTo("PRODUCTO");
        assertThat(r.idea()).isEqualTo("Presumir la vista");
    }

    @Test
    @DisplayName("con la marca incompleta no descarta: lo manda a observación")
    void sinMarcaNoDescarta() throws Exception {
        var r = revisor.interpretar("{\"veredicto\":\"DESCARTADA\",\"motivo\":\"Otro giro\"}", false);
        assertThat(r.veredicto()).isEqualTo(Veredicto.OBSERVACION);
    }

    @Test
    @DisplayName("un veredicto que no se entiende no se adivina: observación")
    void veredictoRaro() throws Exception {
        assertThat(revisor.interpretar("{\"veredicto\":\"QUIZAS\"}", true).veredicto())
                .isEqualTo(Veredicto.OBSERVACION);
    }

    @Test
    @DisplayName("sin JSON no hay revisión: la foto queda pendiente")
    void sinJson() throws Exception {
        assertThat(revisor.interpretar("no puedo ver la imagen", true)).isNull();
    }
}
