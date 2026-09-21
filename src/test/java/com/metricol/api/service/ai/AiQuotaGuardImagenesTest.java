package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.repository.AiUsageRepository;
import com.metricol.api.service.CuentaSinLimites;
import com.metricol.api.service.limits.LimitesConfigurables;

/** El tope diario de imágenes de campaña, aparte del de llamadas de texto. */
class AiQuotaGuardImagenesTest {

    private AiUsageRepository usos;
    private LimitesConfigurables limites;
    private CuentaSinLimites sinLimites;
    private AiQuotaGuard guarda;

    @BeforeEach
    void preparar() {
        usos = mock(AiUsageRepository.class);
        limites = mock(LimitesConfigurables.class);
        TenantIdentifierResolver tenants = mock(TenantIdentifierResolver.class);
        when(tenants.resolveCurrentTenantIdentifier()).thenReturn(UUID.randomUUID().toString());
        sinLimites = mock(CuentaSinLimites.class);
        guarda = new AiQuotaGuard(usos, limites, tenants, sinLimites);
    }

    private void hechasHoy(long cuantas) {
        when(usos.countByWorkspaceIdAndOperacionAndCreatedAtBetween(
                any(UUID.class), eq(AiOperacion.GENERAR_IMAGEN), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(cuantas);
    }

    @Test
    @DisplayName("devuelve cuántas imágenes quedarán después de esta campaña")
    void devuelveLasRestantes() {
        when(limites.maxImagenesIaPorDia()).thenReturn(10);
        hechasHoy(4);

        assertThat(guarda.exigirCupoImagenes(3)).isEqualTo(3);
    }

    @Test
    @DisplayName("un carrusel de cinco no empieza si solo caben tres")
    void nadaDeMedias() {
        when(limites.maxImagenesIaPorDia()).thenReturn(10);
        hechasHoy(7);

        assertThatThrownBy(() -> guarda.exigirCupoImagenes(5))
                .isInstanceOfSatisfying(QuotaExceededException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IMAGE_QUOTA_EXCEEDED");
                    assertThat(e.getMessage()).contains("te quedan 3");
                });
    }

    @Test
    @DisplayName("con el día agotado lo dice con el tope")
    void diaAgotado() {
        when(limites.maxImagenesIaPorDia()).thenReturn(10);
        hechasHoy(10);

        assertThatThrownBy(() -> guarda.exigirCupoImagenes(1))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("10 imágenes de hoy");
    }

    @Test
    @DisplayName("tope en 0 significa sin tope")
    void sinTope() {
        when(limites.maxImagenesIaPorDia()).thenReturn(0);

        assertThat(guarda.exigirCupoImagenes(5)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("la cuenta de la casa no tiene tope de imágenes ni de llamadas, aunque el día esté agotado")
    void laCasaNoTieneTope() {
        when(limites.maxImagenesIaPorDia()).thenReturn(10);
        when(limites.maxIaPorDia()).thenReturn(5);
        when(sinLimites.deWorkspace(any(UUID.class))).thenReturn(true);
        hechasHoy(999);

        assertThat(guarda.exigirCupoImagenes(50)).isEqualTo(Integer.MAX_VALUE);
        guarda.exigirCupo();
    }
}
