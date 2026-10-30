package com.metricol.api.service.media.subtitulos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * De palabras a frases para corregir, y de vuelta.
 *
 * <p>La ida agrupa como se habla: corta en las pausas y no deja frases más
 * largas de lo que se lee de un vistazo. La vuelta respeta lo que la persona
 * escribió: si solo cambió letras (una mayúscula, un acento) cada palabra
 * conserva su tiempo exacto; si quitó o agregó palabras, el tiempo de la
 * frase se reparte entre las nuevas según lo largo de cada una.
 */
public final class Frases {

    /** Una pausa así ya es otra frase. */
    static final double PAUSA = 0.7;
    static final int MAX_PALABRAS = 8;
    static final int MAX_LETRAS = 48;

    private Frases() {
    }

    public static List<Frase> agrupar(List<Palabra> palabras) {
        List<Frase> frases = new ArrayList<>();
        List<Palabra> actual = new ArrayList<>();
        int letras = 0;
        for (Palabra p : palabras) {
            boolean corta = !actual.isEmpty() && (p.inicio() - actual.get(actual.size() - 1).fin() > PAUSA
                    || actual.size() >= MAX_PALABRAS
                    || letras + 1 + p.texto().length() > MAX_LETRAS);
            if (corta) {
                frases.add(frase(actual));
                actual = new ArrayList<>();
                letras = 0;
            }
            letras += (actual.isEmpty() ? 0 : 1) + p.texto().length();
            actual.add(p);
        }
        if (!actual.isEmpty()) {
            frases.add(frase(actual));
        }
        return frases;
    }

    /**
     * Las palabras con tiempo a partir de las frases corregidas. Una frase que
     * quedó vacía se quita: es como la persona borra lo que no se dijo.
     */
    public static List<Palabra> palabras(List<Frase> frases) {
        List<Palabra> todas = new ArrayList<>();
        for (Frase f : frases) {
            if (f == null || f.texto() == null) continue;
            String[] nuevas = Arrays.stream(f.texto().strip().split("\\s+")).filter(s -> !s.isBlank())
                    .toArray(String[]::new);
            if (nuevas.length == 0) continue;
            List<Palabra> viejas = f.palabras() == null ? List.of() : f.palabras();
            if (viejas.size() == nuevas.length) {
                for (int i = 0; i < nuevas.length; i++) {
                    Palabra v = viejas.get(i);
                    todas.add(new Palabra(nuevas[i], v.inicio(), Math.max(v.fin(), v.inicio())));
                }
            } else {
                repartir(nuevas, f.inicio(), Math.max(f.fin(), f.inicio()), todas);
            }
        }
        todas.sort((a, b) -> Double.compare(a.inicio(), b.inicio()));
        return todas;
    }

    private static void repartir(String[] nuevas, double inicio, double fin, List<Palabra> destino) {
        int total = Arrays.stream(nuevas).mapToInt(String::length).sum();
        double largo = fin - inicio;
        double cursor = inicio;
        for (String n : nuevas) {
            double tramo = total == 0 ? largo / nuevas.length : largo * n.length() / total;
            destino.add(new Palabra(n, cursor, cursor + tramo));
            cursor += tramo;
        }
    }

    private static Frase frase(List<Palabra> palabras) {
        StringBuilder texto = new StringBuilder();
        for (Palabra p : palabras) {
            if (texto.length() > 0) texto.append(' ');
            texto.append(p.texto());
        }
        return new Frase(palabras.get(0).inicio(), palabras.get(palabras.size() - 1).fin(), texto.toString(),
                List.copyOf(palabras));
    }
}
