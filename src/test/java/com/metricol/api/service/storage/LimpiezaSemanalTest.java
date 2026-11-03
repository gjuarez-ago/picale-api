package com.metricol.api.service.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.storage.R2StorageService.ObjetoGuardado;

class LimpiezaSemanalTest {

    private static final Instant AHORA = Instant.parse("2026-11-08T10:30:00Z");
    private static final Instant HACE_UNA_SEMANA = AHORA.minus(7, ChronoUnit.DAYS);

    private static ObjetoGuardado objeto(String clave, int diasAtras) {
        return new ObjetoGuardado(clave, AHORA.minus(diasAtras, ChronoUnit.DAYS), 1024);
    }

    @Test
    @DisplayName("Se va lo de media/ que ninguna fila nombra y ya tiene más de una semana")
    void sueltoViejo() {
        List<ObjetoGuardado> objetos = List.of(
                objeto("media/ws-borrado/video.mp4", 40),
                objeto("media/ws/vivo.jpg", 40),
                objeto("media/ws/subiendo-ahora.mp4", 0),
                objeto("media/ws/reels/abc.mp4", 40));

        List<ObjetoGuardado> fuera = LimpiezaSemanal.sueltos(objetos,
                Set.of("media/ws/vivo.jpg", "media/ws/reels/abc.mp4"), HACE_UNA_SEMANA);

        assertThat(fuera).extracting(ObjetoGuardado::clave).containsExactly("media/ws-borrado/video.mp4");
    }

    @Test
    @DisplayName("Fuera de media/ no se toca nada: el bucket lo comparten otros sistemas")
    void soloMedia() {
        List<ObjetoGuardado> objetos = List.of(
                objeto("vivento365/casa.jpg", 90),
                objeto("comprobantes/recibo.pdf", 90),
                objeto("derivados/miniaturas/x.jpg", 90));

        assertThat(LimpiezaSemanal.sueltos(objetos, Set.of(), HACE_UNA_SEMANA)).isEmpty();
    }

    @Test
    @DisplayName("Sin fecha no se sabe si es de ahora: se queda")
    void sinFecha() {
        List<ObjetoGuardado> objetos = List.of(new ObjetoGuardado("media/ws/x.mp4", null, 10));

        assertThat(LimpiezaSemanal.sueltos(objetos, Set.of(), HACE_UNA_SEMANA)).isEmpty();
    }
}
