package com.metricol.api.service.metricas;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lo que una cuenta enseña con sus propias publicaciones: a qué hora le va
 * mejor y qué hashtags le funcionan o no.
 *
 * <p>Todo se mide contra la MISMA cuenta: el rendimiento de cada publicación se
 * divide entre la mediana de la cuenta, así una panadería con 40 me gusta por
 * publicación y un gimnasio con 4 000 aprenden igual. Una hora o un hashtag
 * "funciona" si lo que salió con él rinde por encima de lo normal de la cuenta.
 *
 * <p>Con pocos datos no se concluye nada: menos de {@link #MIN_MUESTRAS}
 * publicaciones medidas y el agente sigue con sus horas de siempre. Sin estado
 * y sin base de datos, para probarlo solo.
 */
public final class AprendizajeDeRendimiento {

    /** Con menos publicaciones medidas, lo que se vea es ruido. */
    public static final int MIN_MUESTRAS = 8;
    /** Cuántas veces tiene que aparecer un hashtag para juzgarlo. */
    static final int MIN_USOS_HASHTAG = 3;
    /** Rinde «bien» si su mediana pasa esto (25 % sobre lo normal de la cuenta). */
    static final double BUENO = 1.25;
    /** Rinde «flojo» si su mediana no llega a esto. */
    static final double FLOJO = 0.7;
    /** Una hora cuenta como buena si su promedio suavizado pasa esto. */
    static final double HORA_BUENA = 1.05;

    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]{2,40})");

    /** Una publicación medida: cuándo salió, con qué texto y cuánto rindió. */
    public record Muestra(LocalDateTime cuando, String texto, double puntaje) {
    }

    /**
     * @param medidas        cuántas publicaciones medidas hubo
     * @param horas          las horas que mejor le funcionan, de mejor a peor; vacía si no hay datos
     * @param hashtagsBuenos los que rinden por encima de lo normal, de mejor a peor
     * @param hashtagsFlojos los que rinden por debajo
     */
    public record Aprendido(int medidas, List<LocalTime> horas, List<String> hashtagsBuenos,
            List<String> hashtagsFlojos) {

        public static final Aprendido NADA = new Aprendido(0, List.of(), List.of(), List.of());

        public boolean suficiente() {
            return medidas >= MIN_MUESTRAS;
        }
    }

    private AprendizajeDeRendimiento() {
    }

    public static Aprendido de(List<Muestra> muestras) {
        List<Muestra> validas = muestras == null ? List.of()
                : muestras.stream().filter(m -> m != null && m.cuando() != null && m.puntaje() >= 0).toList();
        if (validas.size() < MIN_MUESTRAS) {
            return new Aprendido(validas.size(), List.of(), List.of(), List.of());
        }
        double mediana = mediana(validas.stream().map(Muestra::puntaje).toList());
        if (mediana <= 0) {
            // Todo en cero: no hay de dónde aprender.
            return new Aprendido(validas.size(), List.of(), List.of(), List.of());
        }
        List<double[]> relativas = new ArrayList<>(); // {hora, ratio}
        Map<String, List<Double>> porHashtag = new HashMap<>();
        for (Muestra m : validas) {
            double ratio = m.puntaje() / mediana;
            relativas.add(new double[] { m.cuando().getHour(), ratio });
            for (String h : hashtags(m.texto())) {
                porHashtag.computeIfAbsent(h, k -> new ArrayList<>()).add(ratio);
            }
        }
        return new Aprendido(validas.size(), horas(relativas), juzgar(porHashtag, true), juzgar(porHashtag, false));
    }

    /**
     * Las horas ordenadas de mejor a peor. Cada hora se suaviza con sus
     * vecinas (la de antes y la de después pesan la mitad): con 20 publicaciones
     * repartidas en el día, una hora sola casi nunca tiene datos suficientes,
     * pero «entre las 6 y las 8 de la tarde» sí.
     */
    static List<LocalTime> horas(List<double[]> relativas) {
        double[] suma = new double[24];
        int[] cuenta = new int[24];
        for (double[] r : relativas) {
            int h = (int) r[0];
            suma[h] += r[1];
            cuenta[h]++;
        }
        List<double[]> candidatas = new ArrayList<>(); // {hora, valor}
        for (int h = 0; h < 24; h++) {
            double s = 0;
            double peso = 0;
            int n = 0;
            for (int d = -1; d <= 1; d++) {
                int v = h + d;
                if (v < 0 || v > 23 || cuenta[v] == 0) {
                    continue;
                }
                double w = d == 0 ? 1.0 : 0.5;
                s += w * suma[v];
                peso += w * cuenta[v];
                n += cuenta[v];
            }
            // Al menos dos publicaciones en la ventana, y una en la hora misma.
            if (n >= 2 && cuenta[h] > 0 && peso > 0) {
                double valor = s / peso;
                if (valor >= HORA_BUENA) {
                    candidatas.add(new double[] { h, valor });
                }
            }
        }
        candidatas.sort(Comparator.comparingDouble((double[] c) -> c[1]).reversed());
        return candidatas.stream().limit(6).map(c -> LocalTime.of((int) c[0], 0)).toList();
    }

    private static List<String> juzgar(Map<String, List<Double>> porHashtag, boolean buenos) {
        List<Map.Entry<String, Double>> juzgados = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : porHashtag.entrySet()) {
            if (e.getValue().size() < MIN_USOS_HASHTAG) {
                continue;
            }
            // Conservador: para llamarlo bueno tiene que pasar la mitad de abajo, y
            // para llamarlo flojo, la de arriba. Uno que sale igual en lo que
            // funcionó y en lo que no queda neutral, no "bueno" por promedio.
            List<Double> orden = new ArrayList<>(e.getValue());
            orden.sort(Double::compare);
            int n = orden.size();
            double m = buenos ? orden.get((n - 1) / 2) : orden.get(n / 2);
            if (buenos ? m >= BUENO : m <= FLOJO) {
                juzgados.add(Map.entry(e.getKey(), m));
            }
        }
        Comparator<Map.Entry<String, Double>> orden = Map.Entry.comparingByValue();
        juzgados.sort(buenos ? orden.reversed() : orden);
        return juzgados.stream().limit(buenos ? 6 : 4).map(e -> "#" + e.getKey()).toList();
    }

    /** Los hashtags de un texto, en minúsculas y sin repetir. */
    static Set<String> hashtags(String texto) {
        Set<String> todos = new LinkedHashSet<>();
        if (texto == null) {
            return todos;
        }
        Matcher m = HASHTAG.matcher(texto);
        while (m.find()) {
            todos.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return todos;
    }

    static double mediana(List<Double> valores) {
        List<Double> orden = new ArrayList<>(valores);
        orden.sort(Double::compare);
        int n = orden.size();
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? orden.get(n / 2) : (orden.get(n / 2 - 1) + orden.get(n / 2)) / 2.0;
    }
}
