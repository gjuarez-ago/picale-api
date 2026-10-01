package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cuándo propone el agente: pocas por día, en horas fijas, sin pisarse. */
class CalendarioDelAgenteTest {

    private static final LocalDateTime LUNES_9AM = LocalDateTime.of(2026, 10, 5, 9, 0);

    @Test
    @DisplayName("deja margen para revisar: a las 9 no propone las 11 del mismo día, sí las 18")
    void dejaMargen() {
        assertThat(CalendarioDelAgente.siguienteHueco(LUNES_9AM, List.of(), 2))
                .isEqualTo(LocalDateTime.of(2026, 10, 5, 18, 0));
    }

    @Test
    @DisplayName("treinta fotos no son treinta publicaciones mañana: se reparten de dos en dos")
    void reparte() {
        List<LocalDateTime> tomados = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tomados.add(CalendarioDelAgente.siguienteHueco(LUNES_9AM, tomados, 2));
        }
        assertThat(tomados).containsExactly(
                LocalDateTime.of(2026, 10, 5, 18, 0),
                LocalDateTime.of(2026, 10, 6, 11, 0),
                LocalDateTime.of(2026, 10, 6, 18, 0),
                LocalDateTime.of(2026, 10, 7, 11, 0),
                LocalDateTime.of(2026, 10, 7, 18, 0),
                LocalDateTime.of(2026, 10, 8, 11, 0));
    }

    @Test
    @DisplayName("con tope de una al día, una por día")
    void unaAlDia() {
        List<LocalDateTime> tomados = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tomados.add(CalendarioDelAgente.siguienteHueco(LUNES_9AM, tomados, 1));
        }
        assertThat(tomados).extracting(LocalDateTime::toLocalDate).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("no se pone a menos de dos horas de algo que ya estaba programado")
    void respetaLoProgramado() {
        List<LocalDateTime> programado = List.of(LocalDateTime.of(2026, 10, 5, 17, 30));
        assertThat(CalendarioDelAgente.siguienteHueco(LUNES_9AM, programado, 2))
                .isEqualTo(LocalDateTime.of(2026, 10, 6, 11, 0));
    }
}
