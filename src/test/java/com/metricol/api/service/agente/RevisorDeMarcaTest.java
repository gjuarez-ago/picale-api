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
    @DisplayName("lee las señales de autenticidad y las guarda; sin ellas, nada que dudar")
    void autenticidad() throws Exception {
        var r = revisor.interpretar("{\"veredicto\":\"VA\",\"pareceIa\":true,\"personaRealista\":true,"
                + "\"causaSocial\":false,\"lugarDelNegocio\":false}", true);
        assertThat(r.autenticidad().pareceIa()).isTrue();
        assertThat(r.autenticidad().personaRealista()).isTrue();
        String json = RevisorDeMarca.aJson(r, false);
        assertThat(RevisorDeMarca.pareceIa(json)).isTrue();
        assertThat(RevisorDeMarca.deJson(json).autenticidad()).isEqualTo(r.autenticidad());

        assertThat(revisor.interpretar("{\"veredicto\":\"VA\"}", true).autenticidad())
                .isEqualTo(RevisorDeMarca.Autenticidad.NINGUNA);
    }

    @Test
    @DisplayName("la regla de IA: persona, causa o lugar del negocio se preguntan; un diseño con IA va")
    void reglaDeIa() {
        assertThat(new RevisorDeMarca.Autenticidad(true, true, false, false).duda()).contains("persona hecha con IA");
        assertThat(new RevisorDeMarca.Autenticidad(true, true, true, false).duda()).contains("causa social");
        assertThat(new RevisorDeMarca.Autenticidad(true, false, false, true).duda()).contains("lugar hecho con IA");
        // Un diseño o ilustración con IA, sin personas ni lugares: va (con su etiqueta al publicar).
        assertThat(new RevisorDeMarca.Autenticidad(true, false, false, false).duda()).isNull();
        // Una foto real de una persona o de una causa: nada que preguntar.
        assertThat(new RevisorDeMarca.Autenticidad(false, true, true, true).duda()).isNull();
    }

    @Test
    @DisplayName("lee el diagnóstico completo, y lo que falta se toma como una foto correcta")
    void diagnostico() throws Exception {
        var completo = revisor.interpretar("{\"veredicto\":\"VA\",\"tipo\":\"PROMOCION\",\"calidad\":2,"
                + "\"queFalla\":\"oscura\",\"arreglable\":true,\"fuerza\":3,\"esArte\":false,"
                + "\"necesitaTexto\":true,\"intencion\":\"vender\"}", true).diagnostico();
        assertThat(completo.calidad()).isEqualTo(2);
        assertThat(completo.queFalla()).isEqualTo("oscura");
        assertThat(completo.necesitaTexto()).isTrue();
        assertThat(completo.intencion()).isEqualTo(DecisorDelAgente.Intencion.VENDER);

        var minimo = revisor.interpretar("{\"veredicto\":\"VA\"}", true).diagnostico();
        assertThat(minimo.calidad()).isEqualTo(3);
        assertThat(minimo.fuerza()).isEqualTo(3);
        assertThat(minimo.esArte()).isFalse();
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
