package com.metricol.api.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.config.MediaLimitsProperties;
import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.CuentaSinLimites;

/**
 * Espacio y tope por archivo: los negocios normales con lo de siempre, los de una organización
 * sin límites con el suyo.
 */
class StorageQuotaSinLimitesTest {

    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * MB;

    private final UUID normal = UUID.randomUUID();
    private final UUID dueno = UUID.randomUUID();

    private MediaAssetRepository repo;
    private TenantIdentifierResolver tenant;
    private StorageQuotaService servicio;

    @BeforeEach
    void preparar() {
        MediaLimitsProperties props = new MediaLimitsProperties();
        // Producción: 10 GB por negocio y 500 MB por archivo.
        props.setMaxBytesPerWorkspace(10 * GB);
        props.setMaxFileBytes(500 * MB);

        repo = mock(MediaAssetRepository.class);
        when(repo.espacioUsado()).thenReturn(3 * GB);

        tenant = mock(TenantIdentifierResolver.class);

        CuentaSinLimites sinLimites = mock(CuentaSinLimites.class);
        when(sinLimites.deWorkspace(dueno)).thenReturn(true);
        when(sinLimites.deWorkspace(normal)).thenReturn(false);

        servicio = new StorageQuotaService(repo, props, tenant, sinLimites);
    }

    @Test
    @DisplayName("un negocio normal tiene 10 GB y 500 MB por archivo")
    void negocioNormal() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(normal.toString());

        assertThat(servicio.uso().limite()).isEqualTo(10 * GB);
        assertThat(servicio.topePorArchivo()).isEqualTo(500 * MB);
        assertThatThrownBy(() -> servicio.verificar(600 * MB, "video.mp4"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    @DisplayName("un negocio sin límites tiene 100 GB y 2 GB por archivo")
    void negocioSinLimites() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(dueno.toString());

        assertThat(servicio.uso().limite()).isEqualTo(100 * GB);
        assertThat(servicio.uso().disponible()).isEqualTo(97 * GB);
        assertThat(servicio.topePorArchivo()).isEqualTo(2 * GB);
        servicio.verificar(1500 * MB, "VIDEO_3.mp4");
        assertThatThrownBy(() -> servicio.verificar(3 * GB, "enorme.mp4"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    @DisplayName("fuera de un negocio (GLOBAL) aplica el tope de todos")
    void fueraDeUnNegocio() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(TenantIdentifierResolver.GLOBAL_TENANT);

        assertThat(servicio.uso().limite()).isEqualTo(10 * GB);
        assertThat(servicio.topePorArchivo()).isEqualTo(500 * MB);
    }

    @Test
    @DisplayName("el camino viejo por multipart no sube: nginx corta en 100 MB")
    void multipartSigueEn100() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(dueno.toString());

        assertThatThrownBy(() -> servicio.verificarSubidaDirecta(150 * MB, "foto.jpg"))
                .isInstanceOf(QuotaExceededException.class);
    }
}
