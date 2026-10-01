package com.metricol.api.service.agente;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
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
     * El primer hueco libre.
     *
     * @param ahora     desde cuándo se busca
     * @param tomados   lo ya programado o propuesto en la cuenta
     * @param maxPorDia cuántas como mucho en un mismo día (el tope de la
     *                  cuenta, y nunca más que las horas que hay)
     */
    public static LocalDateTime siguienteHueco(LocalDateTime ahora, Collection<LocalDateTime> tomados, int maxPorDia) {
        int tope = Math.max(1, Math.min(maxPorDia, HORAS.size()));
        LocalDateTime desde = ahora.plusHours(MARGEN_HORAS);

        Map<LocalDate, Integer> porDia = new HashMap<>();
        for (LocalDateTime t : tomados) {
            if (t != null) {
                porDia.merge(t.toLocalDate(), 1, Integer::sum);
            }
        }

        for (int d = 0; d <= DIAS_MAXIMOS; d++) {
            LocalDate dia = desde.toLocalDate().plusDays(d);
            if (porDia.getOrDefault(dia, 0) >= tope) {
                continue;
            }
            for (LocalTime hora : HORAS) {
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
        return desde.toLocalDate().plusDays(DIAS_MAXIMOS + 1L).atTime(HORAS.get(0));
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
