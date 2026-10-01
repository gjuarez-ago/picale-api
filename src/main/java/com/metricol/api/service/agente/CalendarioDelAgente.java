package com.metricol.api.service.agente;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cuándo propone el agente que salga cada publicación.
 *
 * <p>La frecuencia no se configura: sale de lo que se sube. Cada propuesta
 * toma el primer hueco libre a partir de ahora, con dos reglas que vienen de
 * cómo trabaja un community manager:
 * <ul>
 * <li><b>Pocas por día.</b> Treinta fotos subidas el lunes no son treinta
 * publicaciones el martes: se reparten en los días siguientes.</li>
 * <li><b>Horas fijas.</b> Media mañana y tarde, que es cuando más se mira en
 * México. Cuando haya métricas, estas horas saldrán de cada cuenta.</li>
 * </ul>
 *
 * <p>Sin estado y sin base de datos a propósito: recibe los huecos ya tomados
 * y devuelve el siguiente. Así se prueba sin levantar nada.
 */
public final class CalendarioDelAgente {

    /** Las horas en que propone publicar, en orden. */
    static final List<LocalTime> HORAS = List.of(LocalTime.of(11, 0), LocalTime.of(18, 0));

    /**
     * Cuánto margen deja antes de la primera: quien aprueba necesita tiempo
     * para ver la propuesta, y una que vence a los diez minutos no se aprueba.
     */
    static final int MARGEN_HORAS = 3;

    /** Hasta dónde busca. Pasado esto la cuenta tiene material de sobra. */
    static final int DIAS_MAXIMOS = 60;

    private CalendarioDelAgente() {
    }

    /**
     * Cuándo publica el negocio: qué días y entre qué horas. Se configura una
     * vez por cuenta, junto al switch del agente.
     *
     * @param desde hora de inicio, 0–23
     * @param hasta hora de fin, 1–24; una publicación a las {@code hasta} ya no cabe
     */
    public record Horario(Set<DayOfWeek> dias, int desde, int hasta) {

        public static final Horario SIEMPRE = new Horario(EnumSet.allOf(DayOfWeek.class), 9, 21);

        public Horario {
            dias = dias == null || dias.isEmpty() ? EnumSet.allOf(DayOfWeek.class) : EnumSet.copyOf(dias);
            desde = Math.max(0, Math.min(23, desde));
            hasta = Math.max(desde + 1, Math.min(24, hasta));
        }

        /** Las horas fijas que caen dentro del horario; si ninguna cae, la mitad del horario. */
        List<LocalTime> horas() {
            List<LocalTime> dentro = HORAS.stream()
                    .filter(h -> h.getHour() >= desde && h.getHour() < hasta)
                    .toList();
            return dentro.isEmpty() ? List.of(LocalTime.of((desde + hasta) / 2, 0)) : dentro;
        }
    }

    /** Como {@link #siguienteHueco(LocalDateTime, Collection, int, Horario)}, cualquier día de 9 a 21. */
    public static LocalDateTime siguienteHueco(LocalDateTime ahora, Collection<LocalDateTime> tomados, int maxPorDia) {
        return siguienteHueco(ahora, tomados, maxPorDia, Horario.SIEMPRE);
    }

    /**
     * El primer hueco libre.
     *
     * @param ahora     desde cuándo se busca
     * @param tomados   lo ya programado o propuesto en la cuenta
     * @param maxPorDia cuántas como mucho en un mismo día (el tope de la
     *                  cuenta, y nunca más que las horas que hay)
     * @param horario   los días y horas en que publica el negocio
     */
    public static LocalDateTime siguienteHueco(LocalDateTime ahora, Collection<LocalDateTime> tomados, int maxPorDia,
            Horario horario) {
        List<LocalTime> horas = horario.horas();
        int tope = Math.max(1, Math.min(maxPorDia, horas.size()));
        LocalDateTime desde = ahora.plusHours(MARGEN_HORAS);

        Map<LocalDate, Integer> porDia = new HashMap<>();
        for (LocalDateTime t : tomados) {
            if (t != null) {
                porDia.merge(t.toLocalDate(), 1, Integer::sum);
            }
        }

        for (int d = 0; d <= DIAS_MAXIMOS; d++) {
            LocalDate dia = desde.toLocalDate().plusDays(d);
            if (!horario.dias().contains(dia.getDayOfWeek()) || porDia.getOrDefault(dia, 0) >= tope) {
                continue;
            }
            for (LocalTime hora : horas) {
                LocalDateTime hueco = dia.atTime(hora);
                if (hueco.isBefore(desde)) {
                    continue;
                }
                if (ocupado(hueco, tomados)) {
                    continue;
                }
                return hueco;
            }
        }
        // Sin hueco en dos meses: va al final. La persona igual decide.
        return desde.toLocalDate().plusDays(DIAS_MAXIMOS + 1L).atTime(horas.get(0));
    }

    /** Ya hay algo a menos de dos horas: dos seguidas en la misma cuenta se pisan. */
    private static boolean ocupado(LocalDateTime hueco, Collection<LocalDateTime> tomados) {
        for (LocalDateTime t : tomados) {
            if (t != null && Math.abs(java.time.Duration.between(t, hueco).toMinutes()) < 120) {
                return true;
            }
        }
        return false;
    }
}
