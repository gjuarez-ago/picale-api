package com.metricol.api.service.agente.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;

class RendimientoPorRedTest {

    @Test
    void excluyeUnaRedSoloConMuestraSuficienteYDiferenciaClara() {
        List<Object[]> filas = new ArrayList<>();
        tres(filas, Platform.YOUTUBE, PostFormat.VIDEO, 10_000, 500, 30, 20, 25);
        tres(filas, Platform.TIKTOK, PostFormat.VIDEO, 500, 10, 1, 0, 0);

        var seleccion = RendimientoPorRed.calcular(
                Set.of(Platform.YOUTUBE, Platform.TIKTOK), PostFormat.VIDEO, filas);

        assertThat(seleccion.elegidas()).containsExactly(Platform.YOUTUBE);
        assertThat(seleccion.evaluaciones().get(Platform.YOUTUBE).puntaje()).isEqualTo(100.0);
        assertThat(seleccion.evaluaciones().get(Platform.TIKTOK).elegida()).isFalse();
        assertThat(seleccion.evaluaciones().get(Platform.TIKTOK).frase()).contains("debajo del 65%");
    }

    @Test
    void pocosDatosNoCastiganALaRed() {
        List<Object[]> filas = new ArrayList<>();
        tres(filas, Platform.YOUTUBE, PostFormat.VIDEO, 10_000, 500, 30, 20, 25);
        filas.add(fila(Platform.TIKTOK, PostFormat.VIDEO, 10));

        var seleccion = RendimientoPorRed.calcular(
                Set.of(Platform.YOUTUBE, Platform.TIKTOK), PostFormat.VIDEO, filas);

        assertThat(seleccion.elegidas()).containsExactlyInAnyOrder(Platform.YOUTUBE, Platform.TIKTOK);
        assertThat(seleccion.evaluaciones().get(Platform.TIKTOK).puntaje()).isNull();
        assertThat(seleccion.evaluaciones().get(Platform.TIKTOK).frase()).contains("aún no hay 3 videos");
    }

    @Test
    void prefiereLaHistoriaDelMismoFormato() {
        List<Object[]> filas = new ArrayList<>();
        tres(filas, Platform.YOUTUBE, PostFormat.REEL, 100, 0, 0, 0, 0);
        tres(filas, Platform.YOUTUBE, PostFormat.VIDEO, 10_000, 100, 10, 5, 5);
        tres(filas, Platform.TIKTOK, PostFormat.REEL, 1_000, 20, 4, 2, 2);
        tres(filas, Platform.TIKTOK, PostFormat.VIDEO, 10, 0, 0, 0, 0);

        var seleccion = RendimientoPorRed.calcular(
                Set.of(Platform.YOUTUBE, Platform.TIKTOK), PostFormat.REEL, filas);

        assertThat(seleccion.elegidas()).containsExactly(Platform.TIKTOK);
    }

    @Test
    void tresVideosEnCeroSiguenSiendoHistoriaValida() {
        List<Object[]> filas = new ArrayList<>();
        tres(filas, Platform.YOUTUBE, PostFormat.VIDEO, 0, 0, 0, 0, 0);
        tres(filas, Platform.TIKTOK, PostFormat.VIDEO, 0, 0, 0, 0, 0);

        var seleccion = RendimientoPorRed.calcular(
                Set.of(Platform.YOUTUBE, Platform.TIKTOK), PostFormat.VIDEO, filas);

        assertThat(seleccion.elegidas()).containsExactlyInAnyOrder(Platform.YOUTUBE, Platform.TIKTOK);
        assertThat(seleccion.evaluaciones().values())
                .allSatisfy(e -> assertThat(e.puntaje()).isEqualTo(100.0));
    }

    private static void tres(List<Object[]> filas, Platform red, PostFormat formato,
            long vistas, long likes, long comentarios, long compartidos, long guardados) {
        for (int i = 0; i < 3; i++) {
            filas.add(new Object[] { red.name(), formato.name(), vistas, null, likes,
                    comentarios, compartidos, guardados });
        }
    }

    private static Object[] fila(Platform red, PostFormat formato, long vistas) {
        return new Object[] { red.name(), formato.name(), vistas, null, 0L, 0L, 0L, 0L };
    }
}
