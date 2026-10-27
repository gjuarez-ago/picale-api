package com.metricol.api.service.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Platform;
import com.metricol.api.repository.DailyPublishUsageRepository;
import com.metricol.api.repository.GlobalPublishUsageRepository;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.service.CuentaSinLimites;
import com.metricol.api.service.limits.LimitesConfigurables;
import com.metricol.api.service.publishing.PublishQuotaService.Reserva;

/**
 * El tope de cada red lo pone el proveedor y protege la cuenta: alcanza también a la cuenta de la casa.
 *
 * <p>El 27 oct 2026 la cuenta sin límites de Juan Rodriguez publicó 22 veces en Facebook en una hora
 * —las propuestas de dos semanas, todas con "Publicar ahora"— y upload-post rechazó las siguientes con
 * "daily posting cap on facebook (25 posts per 24 hours)". Nuestro tope ni se miró: la cuenta de la casa
 * se lo saltaba entero.
 */
class TopeDeRedCuentaDeLaCasaTest {

    private final UUID casa = UUID.randomUUID();

    private DailyPublishUsageRepository contadorDelDia;
    private GlobalPublishUsageRepository global;
    private PostTargetRepository destinos;
    private PublishQuotaService cuotas;

    @BeforeEach
    void preparar() {
        contadorDelDia = mock(DailyPublishUsageRepository.class);
        global = mock(GlobalPublishUsageRepository.class);
        destinos = mock(PostTargetRepository.class);

        LimitesConfigurables limites = mock(LimitesConfigurables.class);
        when(limites.cuotaDiaria(Platform.FACEBOOK)).thenReturn(20);
        when(limites.ventanaRodanteHoras()).thenReturn(24);

        CuentaSinLimites sinLimites = mock(CuentaSinLimites.class);
        when(sinLimites.deWorkspace(casa)).thenReturn(true);

        cuotas = new PublishQuotaService(contadorDelDia, mock(DailyUsageRows.class), global,
                mock(GlobalUsageRows.class), destinos, limites, sinLimites);
    }

    @Test
    @DisplayName("con 20 publicadas a Facebook en 24 h, la cuenta de la casa también se frena")
    void laCasaRespetaElTopeDeLaRed() {
        when(destinos.countPublicadasDesde(eq(casa.toString()), eq(Platform.FACEBOOK), any())).thenReturn(20L);

        assertThat(cuotas.reservar(casa, Platform.FACEBOOK)).isEqualTo(Reserva.RED_AGOTADA);
    }

    @Test
    @DisplayName("por debajo del tope publica, sin gastar el contador del día ni el cupo global")
    void laCasaNoGastaContadores() {
        when(destinos.countPublicadasDesde(eq(casa.toString()), eq(Platform.FACEBOOK), any())).thenReturn(5L);

        assertThat(cuotas.reservar(casa, Platform.FACEBOOK)).isEqualTo(Reserva.OK);
        verify(contadorDelDia, never()).devolver(any(), any(), any());
        verifyNoInteractions(global);
    }

    @Test
    @DisplayName("la pantalla le dice a la casa cuántas lleva en 24 h y su tope, en vez de 'sin tope'")
    void elEstadoDeLaCasaMuestraElTope() {
        when(contadorDelDia.findByWorkspaceIdAndDay(eq(casa), any())).thenReturn(List.of());
        when(destinos.countPublicadasDesde(eq(casa.toString()), any(), any())).thenReturn(0L);
        when(destinos.countPublicadasDesde(eq(casa.toString()), eq(Platform.FACEBOOK), any())).thenReturn(22L);

        PublishQuotaService.Estado facebook = cuotas.estadoDe(casa).stream()
                .filter(e -> e.platform() == Platform.FACEBOOK).findFirst().orElseThrow();
        assertThat(facebook.used()).isEqualTo(22);
        assertThat(facebook.limit()).isEqualTo(20);
    }
}
