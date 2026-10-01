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
    @DisplayName("respeta el horario del negocio: de lunes a viernes, de 9 a 14, solo cabe la de las 11")
    void horarioDelNegocio() {
        var horario = new CalendarioDelAgente.Horario(java.util.EnumSet.range(java.time.DayOfWeek.MONDAY,
                java.time.DayOfWeek.FRIDAY), 9, 14);
        LocalDateTime viernes10 = LocalDateTime.of(2026, 10, 9, 10, 0);
        // El viernes a las 10 ya no da tiempo para las 11; el fin de semana no se publica.
        assertThat(CalendarioDelAgente.siguienteHueco(viernes10, List.of(), 2, horario))
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 11, 0));
    }

    @Test
    @DisplayName("un horario que no cubre las horas fijas publica a la mitad de su horario")
    void horarioAngosto() {
        var horario = new CalendarioDelAgente.Horario(null, 13, 17);
        assertThat(CalendarioDelAgente.siguienteHueco(LUNES_9AM, List.of(), 2, horario))
                .isEqualTo(LocalDateTime.of(2026, 10, 5, 15, 0));
    }

    @Test
    @DisplayName("mezcla: una tercera de venta seguida se mueve después de algo que no es venta, y dice por qué")
    void tresDeVenta() {
        var ventas = new ArrayList<>(List.of(
                new CalendarioDelAgente.Tomado(LocalDateTime.of(2026, 10, 5, 18, 0), "VENTA"),
                new CalendarioDelAgente.Tomado(LocalDateTime.of(2026, 10, 6, 11, 0), "PROMOCION"),
                new CalendarioDelAgente.Tomado(LocalDateTime.of(2026, 10, 7, 11, 0), "COMUNIDAD")));
        var h = CalendarioDelAgente.siguienteHueco(LUNES_9AM, ventas, 2, CalendarioDelAgente.Horario.SIEMPRE,
                CalendarioDelAgente.Categoria.VENTA);
        // El martes a las 18 sería la tercera de venta seguida; el miércoles a las 18, después de la de comunidad, no.
        assertThat(h.cuando()).isEqualTo(LocalDateTime.of(2026, 10, 7, 18, 0));
        assertThat(h.razon()).contains("tres de venta seguidas");
    }

    @Test
    @DisplayName("mezcla: dos promociones no van seguidas")
    void dosPromociones() {
        var promo = List.of(new CalendarioDelAgente.Tomado(LocalDateTime.of(2026, 10, 5, 18, 0), "PROMOCION"));
        var h = CalendarioDelAgente.siguienteHueco(LUNES_9AM, promo, 2, CalendarioDelAgente.Horario.SIEMPRE,
                CalendarioDelAgente.Categoria.PROMOCION);
        assertThat(h.cuando()).isNotEqualTo(LocalDateTime.of(2026, 10, 6, 11, 0));
        assertThat(h.razon()).contains("dos promociones seguidas");
    }

    @Test
    @DisplayName("mezcla: lo hecho a mano (sin categoría) corta la racha; y lo que no es venta va al primer hueco")
    void neutras() {
        var linea = List.of(
                new CalendarioDelAgente.Tomado(LocalDateTime.of(2026, 10, 5, 18, 0), "VENTA"),
                new CalendarioDelAgente.Tomado(LocalDateTime.of(2026, 10, 6, 11, 0), null));
        var venta = CalendarioDelAgente.siguienteHueco(LUNES_9AM, linea, 2, CalendarioDelAgente.Horario.SIEMPRE,
                CalendarioDelAgente.Categoria.VENTA);
        assertThat(venta.cuando()).isEqualTo(LocalDateTime.of(2026, 10, 6, 18, 0));
        assertThat(venta.razon()).isNull();
        var comunidad = CalendarioDelAgente.siguienteHueco(LUNES_9AM, linea, 2, CalendarioDelAgente.Horario.SIEMPRE,
                CalendarioDelAgente.Categoria.COMUNIDAD);
        assertThat(comunidad.razon()).isNull();
    }

    @Test
    @DisplayName("no se pone a menos de dos horas de algo que ya estaba programado")
    void respetaLoProgramado() {
        List<LocalDateTime> programado = List.of(LocalDateTime.of(2026, 10, 5, 17, 30));
        assertThat(CalendarioDelAgente.siguienteHueco(LUNES_9AM, programado, 2))
                .isEqualTo(LocalDateTime.of(2026, 10, 6, 11, 0));
    }
}
