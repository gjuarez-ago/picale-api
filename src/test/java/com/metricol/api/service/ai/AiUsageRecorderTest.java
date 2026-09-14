package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.config.OpenAiProperties;
import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.AiUsage;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.repository.AiUsageRepository;

/**
 * El gasto se anota a nombre de quien lo hizo y con el precio correcto.
 *
 * <p>Equivocar cualquiera de las dos cosas no se nota en ningún lado: el
 * reporte sale igual de ordenado, solo que culpa al cliente equivocado o
 * dice que la IA cuesta la décima parte.
 */
class AiUsageRecorderTest {

    private static final String WS = "44444444-4444-4444-4444-444444444444";

    private AiUsageRepository repository;
    private OpenAiProperties props;
    private AiUsageRecorder recorder;

    @BeforeEach
    void preparar() {
        repository = mock(AiUsageRepository.class);
        props = new OpenAiProperties();
        props.getPricing().setInputUsdPerMillion(new BigDecimal("0.40"));
        props.getPricing().setOutputUsdPerMillion(new BigDecimal("1.60"));
        recorder = new AiUsageRecorder(repository, props, new TenantIdentifierResolver());
    }

    @Test
    @DisplayName("el costo sale de los tokens y del precio por millon")
    void calculaElCosto() {
        // 900 de entrada y 700 de salida: una redaccion para cinco redes.
        // 900 x 0.40 + 700 x 1.60 = 1480 dolares por millon = 0.00148.
        assertThat(AiUsageRecorder.costo(900, 700, props.getPricing()))
                .isEqualByComparingTo("0.00148");
    }

    @Test
    @DisplayName("la llamada queda a nombre del workspace que la pidio")
    void anotaElWorkspace() {
        TenantIdentifierResolver.comoTenant(WS,
                () -> recorder.registrar(AiOperacion.REDACTAR, "gpt-4.1-mini-2025-04-14", 900, 700));

        ArgumentCaptor<AiUsage> guardado = ArgumentCaptor.forClass(AiUsage.class);
        verify(repository).save(guardado.capture());
        AiUsage fila = guardado.getValue();

        assertThat(fila.getWorkspaceId()).isEqualTo(UUID.fromString(WS));
        assertThat(fila.getOperacion()).isEqualTo(AiOperacion.REDACTAR);
        assertThat(fila.getModelo()).isEqualTo("gpt-4.1-mini-2025-04-14");
        assertThat(fila.getTokensEntrada()).isEqualTo(900);
        assertThat(fila.getTokensSalida()).isEqualTo(700);
        assertThat(fila.getCostoUsd()).isEqualByComparingTo("0.00148");
        assertThat(fila.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("sin sesion se anota igual, sin inventarle un workspace")
    void sinSesionNoSeInventaWorkspace() {
        recorder.registrar(AiOperacion.DESCRIBIR_IMAGENES, "gpt-4.1-mini", 2500, 40);

        ArgumentCaptor<AiUsage> guardado = ArgumentCaptor.forClass(AiUsage.class);
        verify(repository).save(guardado.capture());
        assertThat(guardado.getValue().getWorkspaceId()).isNull();
    }

    @Test
    @DisplayName("sin precio configurado se cuentan los tokens y el costo sale en cero")
    void sinPrecioElCostoEsCero() {
        OpenAiProperties sinPrecio = new OpenAiProperties();

        assertThat(sinPrecio.getPricing().isConfigured()).isFalse();
        assertThat(AiUsageRecorder.costo(1000, 1000, sinPrecio.getPricing())).isEqualByComparingTo("0");
    }
}
