package com.metricol.api.service.media;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import com.metricol.api.repository.MediaAssetRepository;

class RecuperadorDePortadasTest {
    @Test
    void recuperaSinReiniciarConTenantYLimiteDeCincoSinRepetirEnCadaVuelta() {
        var assets = mock(MediaAssetRepository.class);
        var miniaturas = mock(MiniaturasEnSegundoPlano.class);
        var reloj = mock(Clock.class);
        var ahora = Instant.parse("2026-11-15T23:00:00Z");
        when(reloj.instant()).thenReturn(ahora);
        when(reloj.getZone()).thenReturn(ZoneOffset.UTC);
        var filas = new ArrayList<Object[]>();
        for (int i = 0; i < 6; i++) filas.add(new Object[]{UUID.randomUUID().toString(), "negocio"});
        when(assets.videosRecientesSinPortada(any(), any())).thenReturn(filas);
        var recuperador = new RecuperadorDePortadas(assets, miniaturas, reloj);
        recuperador.revisar();
        verify(miniaturas, times(5)).sacar(any(), eq("negocio"));
        recuperador.revisar();
        // El sexto avanza mientras los primeros están en espera.
        verify(miniaturas, times(6)).sacar(any(), eq("negocio"));
        recuperador.revisar();
        verifyNoMoreInteractions(miniaturas);
        when(reloj.instant()).thenReturn(ahora.plusSeconds(600));
        recuperador.revisar();
        verify(miniaturas, times(11)).sacar(any(), eq("negocio"));
    }

    @Test
    void unFalloNoImpideRevisarLosDemas() {
        var assets = mock(MediaAssetRepository.class);
        var miniaturas = mock(MiniaturasEnSegundoPlano.class);
        UUID primero = UUID.randomUUID(), segundo = UUID.randomUUID();
        when(assets.videosRecientesSinPortada(any(), any())).thenReturn(List.of(
                new Object[]{primero.toString(), "negocio"},
                new Object[]{segundo.toString(), "negocio"}));
        doThrow(new IllegalStateException("cola llena")).when(miniaturas).sacar(primero, "negocio");
        var recuperador = new RecuperadorDePortadas(assets, miniaturas,
                Clock.fixed(Instant.parse("2026-11-15T23:00:00Z"), ZoneOffset.UTC));
        recuperador.revisar();
        verify(miniaturas).sacar(segundo, "negocio");
    }
}
