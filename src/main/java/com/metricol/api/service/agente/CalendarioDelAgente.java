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
    /**
     * @param preferidas las horas que mejor le funcionan a la cuenta, de mejor a
     *                   peor (ver {@code LoQueFunciona}); vacía = las de siempre
     */
    public record Horario(Set<DayOfWeek> dias, int desde, int hasta, List<LocalTime> preferidas) {

        public static final Horario SIEMPRE = new Horario(EnumSet.allOf(DayOfWeek.class), 9, 21);

        /** Separación mínima entre las dos publicaciones de un día. */
        static final int SEPARACION_HORAS = 3;

        public Horario {
            dias = dias == null || dias.isEmpty() ? EnumSet.allOf(DayOfWeek.class) : EnumSet.copyOf(dias);
            desde = Math.max(0, Math.min(23, desde));
            hasta = Math.max(desde + 1, Math.min(24, hasta));
            preferidas = preferidas == null ? List.of() : List.copyOf(preferidas);
        }

        public Horario(Set<DayOfWeek> dias, int desde, int hasta) {
            this(dias, desde, hasta, List.of());
        }

        /** El mismo horario, con las horas que la cuenta aprendió. */
        public Horario conPreferidas(List<LocalTime> horas) {
            return new Horario(dias, desde, hasta, horas);
        }

        private boolean dentro(LocalTime h) {
            return h.getHour() >= desde && h.getHour() < hasta;
        }

        /**
         * Las horas del día en que se publica, en orden. Con horas aprendidas,
         * las dos mejores que caen en el horario y se separan al menos tres
         * horas (si solo cabe una, la acompaña una de las de siempre). Sin
         * ellas, las fijas que caen dentro; y si ninguna cae, la mitad del horario.
         */
        List<LocalTime> horas() {
            List<LocalTime> elegidas = new java.util.ArrayList<>();
            for (LocalTime p : preferidas) {
                if (dentro(p) && separada(p, elegidas)) {
                    elegidas.add(p);
                }
                if (elegidas.size() == 2) {
                    break;
                }
            }
            if (elegidas.size() == 1) {
                for (LocalTime h : HORAS) {
                    if (dentro(h) && separada(h, elegidas)) {
                        elegidas.add(h);
                        break;
                    }
                }
            }
            if (!elegidas.isEmpty()) {
                elegidas.sort(null);
                return List.copyOf(elegidas);
            }
            List<LocalTime> fijas = HORAS.stream().filter(this::dentro).toList();
            return fijas.isEmpty() ? List.of(LocalTime.of((desde + hasta) / 2, 0)) : fijas;
        }

        private static boolean separada(LocalTime h, List<LocalTime> otras) {
            return otras.stream().allMatch(o -> Math.abs(o.getHour() - h.getHour()) >= SEPARACION_HORAS);
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
        List<LocalDateTime> libres = huecosLibres(ahora, tomados, maxPorDia, horario, 1);
        if (!libres.isEmpty()) {
            return libres.get(0);
        }
        // Sin hueco en dos meses: va al final. La persona igual decide.
        return ahora.plusHours(MARGEN_HORAS).toLocalDate().plusDays(DIAS_MAXIMOS + 1L).atTime(horario.horas().get(0));
    }

    /** Los primeros {@code cuantos} huecos libres, en orden. */
    static List<LocalDateTime> huecosLibres(LocalDateTime ahora, Collection<LocalDateTime> tomados, int maxPorDia,
            Horario horario, int cuantos) {
        return huecosLibres(ahora, tomados, maxPorDia, horario, horario.horas(), cuantos);
    }

    private static List<LocalDateTime> huecosLibres(LocalDateTime ahora, Collection<LocalDateTime> tomados,
            int maxPorDia, Horario horario, List<LocalTime> horas, int cuantos) {
        int tope = Math.max(1, Math.min(maxPorDia, horas.size()));
        LocalDateTime desde = ahora.plusHours(MARGEN_HORAS);

        Map<LocalDate, Integer> porDia = new HashMap<>();
        for (LocalDateTime t : tomados) {
            if (t != null) {
                porDia.merge(t.toLocalDate(), 1, Integer::sum);
            }
        }

        List<LocalDateTime> libres = new java.util.ArrayList<>();
        for (int d = 0; d <= DIAS_MAXIMOS && libres.size() < cuantos; d++) {
            LocalDate dia = desde.toLocalDate().plusDays(d);
            if (!horario.dias().contains(dia.getDayOfWeek())) {
                continue;
            }
            int enElDia = porDia.getOrDefault(dia, 0);
            for (LocalTime hora : horas) {
                if (enElDia >= tope || libres.size() >= cuantos) {
                    break;
                }
                LocalDateTime hueco = dia.atTime(hora);
                if (hueco.isBefore(desde) || ocupado(hueco, tomados)) {
                    continue;
                }
                libres.add(hueco);
                // Cada hueco que se ofrece cuenta para el tope del día: si no,
                // la lista ofrecería tres en un día de dos.
                enElDia++;
            }
        }
        return libres;
    }

    // ------------------------------------------------------------ historias

    /**
     * Las horas de las historias. Llevan su propio ritmo, como en las cuentas
     * grandes: varias al día, aparte del feed, sin quitarle lugar.
     */
    static final List<LocalTime> HORAS_HISTORIA = List.of(
            LocalTime.of(10, 0), LocalTime.of(13, 0), LocalTime.of(17, 0), LocalTime.of(20, 0));

    /** Cuántas historias como mucho en un día. */
    static final int HISTORIAS_POR_DIA = 3;

    /**
     * El siguiente hueco para una historia: en el horario del negocio, sin
     * pasar de {@link #HISTORIAS_POR_DIA} al día y separadas dos horas de las
     * otras historias. El feed no cuenta: son dos ritmos aparte.
     *
     * @param historias las historias ya programadas o propuestas
     */
    public static Hueco siguienteHuecoDeHistoria(LocalDateTime ahora, Collection<LocalDateTime> historias,
            Horario horario) {
        List<LocalTime> horas = HORAS_HISTORIA.stream()
                .filter(h -> h.getHour() >= horario.desde() && h.getHour() < horario.hasta())
                .toList();
        if (horas.isEmpty()) {
            horas = List.of(LocalTime.of((horario.desde() + horario.hasta()) / 2, 0));
        }
        List<LocalDateTime> libres = huecosLibres(ahora, historias, HISTORIAS_POR_DIA, horario, horas, 1);
        LocalDateTime cuando = libres.isEmpty()
                ? ahora.plusHours(MARGEN_HORAS).toLocalDate().plusDays(DIAS_MAXIMOS + 1L).atTime(horas.get(0))
                : libres.get(0);
        return new Hueco(cuando, null);
    }

    // ------------------------------------------------------------ la mezcla

    /** Qué clase de publicación es, para equilibrar la semana. */
    public enum Categoria {
        PROMOCION, VENTA, DIA_A_DIA, COMUNIDAD;

        boolean deVenta() {
            return this == PROMOCION || this == VENTA;
        }

        /** "de venta" o "promociones", para decirle a la persona por qué se movió. */
        static Categoria de(String nombre) {
            if (nombre == null) {
                return null;
            }
            try {
                return valueOf(nombre);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    /** Algo ya en el calendario: cuándo, y de qué clase (nulo = hecha a mano, neutra). */
    public record Tomado(LocalDateTime cuando, String categoria) {
    }

    /**
     * El hueco elegido, y si se saltó el primero libre para no romper la mezcla.
     *
     * @param razon por qué se movió, en palabras para la persona; nulo si no se movió
     */
    public record Hueco(LocalDateTime cuando, String razon) {
    }

    /** Cuántos huecos libres se miran buscando uno que respete la mezcla. */
    static final int CANDIDATOS = 40;

    /**
     * El primer hueco libre que no rompa la mezcla de la semana:
     * <ul>
     * <li>nunca tres de venta seguidas (promoción y producto cuentan igual);</li>
     * <li>nunca dos promociones seguidas.</li>
     * </ul>
     * Si ninguno de los siguientes cumple, el primero libre: el material nunca
     * se queda sin fecha por la mezcla.
     */
    public static Hueco siguienteHueco(LocalDateTime ahora, List<Tomado> tomados, int maxPorDia, Horario horario,
            Categoria categoria) {
        List<LocalDateTime> fechas = tomados.stream().map(Tomado::cuando).toList();
        List<LocalDateTime> libres = huecosLibres(ahora, fechas, maxPorDia, horario, CANDIDATOS);
        if (libres.isEmpty()) {
            return new Hueco(siguienteHueco(ahora, fechas, maxPorDia, horario), null);
        }
        if (categoria == null) {
            return new Hueco(libres.get(0), null);
        }
        String primeraFalla = null;
        for (LocalDateTime hueco : libres) {
            String falla = rompeLaMezcla(hueco, categoria, tomados);
            if (falla == null) {
                return new Hueco(hueco, primeraFalla == null ? null : "La puse aquí para no juntar " + primeraFalla + ".");
            }
            if (primeraFalla == null) {
                primeraFalla = falla;
            }
        }
        return new Hueco(libres.get(0), null);
    }

    /** Qué regla rompería poner esto en ese hueco, o {@code null} si ninguna. */
    static String rompeLaMezcla(LocalDateTime hueco, Categoria categoria, List<Tomado> tomados) {
        List<Tomado> linea = new java.util.ArrayList<>(tomados.stream().filter(t -> t.cuando() != null).toList());
        Tomado nuevo = new Tomado(hueco, categoria.name());
        linea.add(nuevo);
        linea.sort(java.util.Comparator.comparing(Tomado::cuando));
        int i = linea.indexOf(nuevo);

        // Dos promociones seguidas: mira a los dos lados.
        if (categoria == Categoria.PROMOCION) {
            if ((i > 0 && Categoria.de(linea.get(i - 1).categoria()) == Categoria.PROMOCION
                    && cerca(linea.get(i - 1), linea.get(i)))
                    || (i + 1 < linea.size() && Categoria.de(linea.get(i + 1).categoria()) == Categoria.PROMOCION
                            && cerca(linea.get(i), linea.get(i + 1)))) {
                return "dos promociones seguidas";
            }
        }
        // Tres de venta seguidas: la racha que pasa por aquí.
        if (categoria.deVenta()) {
            int racha = 1;
            for (int j = i - 1; j >= 0 && deVenta(linea.get(j)) && cerca(linea.get(j), linea.get(j + 1)); j--) {
                racha++;
            }
            for (int j = i + 1; j < linea.size() && deVenta(linea.get(j)) && cerca(linea.get(j - 1), linea.get(j)); j++) {
                racha++;
            }
            if (racha >= 3) {
                return "tres de venta seguidas";
            }
        }
        return null;
    }

    /**
     * Dos publicaciones solo "van seguidas" si están a menos de tres días: una
     * promoción hoy y otra la semana que entra no se estorban, aunque no haya
     * nada en medio.
     */
    static final long VENTANA_HORAS = 72;

    private static boolean cerca(Tomado antes, Tomado despues) {
        return java.time.Duration.between(antes.cuando(), despues.cuando()).toHours() < VENTANA_HORAS;
    }

    private static boolean deVenta(Tomado t) {
        Categoria c = Categoria.de(t.categoria());
        return c != null && c.deVenta();
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
