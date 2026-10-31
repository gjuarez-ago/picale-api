package com.metricol.api.service.media.subtitulos;

import java.util.List;

/**
 * Si un video ya trae música (o sonido de fondo fuerte), para no ponerle otra
 * encima. Se escucha en las pausas: entre palabra y palabra, un video con
 * música sigue sonando y uno sin ella baja casi al silencio.
 *
 * <p>Calibrado el 31 oct 2026 con videos reales de una cuenta y con controles
 * a los que se les mezcló música del catálogo: los que traen música quedan
 * arriba de -38 dB más del 72% de las pausas; una grabación en un cuarto
 * tranquilo, en 25% o menos; una voz grabada limpia, en 1%.
 */
public final class FondoDelAudio {

    /** Más fuerte que esto, en una pausa, es que algo suena de fondo. */
    static final double UMBRAL_DB = -38;
    /** Qué parte de las pausas tiene que sonar para decir que ya trae fondo. */
    static final double PARTE = 0.6;
    /** Con menos pausas que esto (en décimas) no se puede saber. */
    static final int PAUSAS_MINIMAS = 15;
    /** Lo que se le da a cada palabra antes y después: la voz no termina en seco. */
    private static final double MARGEN = 0.3;
    /** El primer y último segundo no cuentan: ahí suelen ir golpes o silencios de edición. */
    private static final double ORILLA = 1.0;

    private FondoDelAudio() {
    }

    /**
     * @param niveles  dB cada décima de segundo
     * @param palabras lo que se dice, con sus tiempos (vacío si nadie habla)
     * @return si ya trae fondo. Sin pausas suficientes para escuchar dice que
     *         sí: ante la duda, el video se deja como lo subieron.
     */
    public static boolean yaTraeFondo(double[] niveles, List<Palabra> palabras) {
        double duracion = niveles.length / 10.0;
        int pausas = 0;
        int suenan = 0;
        int p = 0;
        for (int i = 0; i < niveles.length; i++) {
            double t = i / 10.0 + 0.05;
            if (t < ORILLA || t > duracion - ORILLA) continue;
            while (p < palabras.size() && palabras.get(p).fin() + MARGEN < t) p++;
            boolean habla = p < palabras.size() && palabras.get(p).inicio() - MARGEN <= t;
            if (habla) continue;
            pausas++;
            if (niveles[i] > UMBRAL_DB) suenan++;
        }
        if (pausas < PAUSAS_MINIMAS) {
            return true;
        }
        return suenan >= PARTE * pausas;
    }
}
