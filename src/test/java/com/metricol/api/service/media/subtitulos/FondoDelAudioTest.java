package com.metricol.api.service.media.subtitulos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Si un video ya trae música: se escucha en las pausas entre palabras. */
class FondoDelAudioTest {

    /** 20 s: habla del 2 al 6 y del 10 al 14; lo demás son pausas. */
    private static final List<Palabra> HABLA = List.of(new Palabra("uno", 2, 6), new Palabra("dos", 10, 14));

    private static double[] niveles(double voz, double pausa) {
        double[] n = new double[200];
        for (int i = 0; i < n.length; i++) {
            double t = i / 10.0;
            n[i] = (t >= 2 && t <= 6) || (t >= 10 && t <= 14) ? voz : pausa;
        }
        return n;
    }

    @Test
    @DisplayName("Con música, las pausas siguen sonando; con voz limpia o un cuarto tranquilo, no")
    void pausas() {
        assertThat(FondoDelAudio.yaTraeFondo(niveles(-18, -26), HABLA)).isTrue();
        assertThat(FondoDelAudio.yaTraeFondo(niveles(-18, -90), HABLA)).isFalse();
        assertThat(FondoDelAudio.yaTraeFondo(niveles(-28, -42), HABLA)).isFalse();
    }

    @Test
    @DisplayName("Sin voz, todo el video es pausa")
    void sinVoz() {
        double[] quieto = new double[100];
        Arrays.fill(quieto, -55);
        assertThat(FondoDelAudio.yaTraeFondo(quieto, List.of())).isFalse();
        double[] cancion = new double[100];
        Arrays.fill(cancion, -16);
        assertThat(FondoDelAudio.yaTraeFondo(cancion, List.of())).isTrue();
    }

    @Test
    @DisplayName("Si habla sin pausas no se puede saber: se deja como lo subieron")
    void sinPausas() {
        double[] n = new double[60];
        Arrays.fill(n, -20);
        assertThat(FondoDelAudio.yaTraeFondo(n, List.of(new Palabra("todo", 0, 6)))).isTrue();
    }
}
