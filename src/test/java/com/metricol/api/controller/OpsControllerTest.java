package com.metricol.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.AiUsageReportResponse;
import com.metricol.api.service.ai.AiUsageReportService;
import com.metricol.api.service.social.ReconciliacionUploadPost;

/**
 * La llave del reporte de gasto. Es lo único que separa los datos de todos los
 * clientes de cualquiera que conozca la URL.
 */
class OpsControllerTest {

    private AiUsageReportService reporte;

    @BeforeEach
    void preparar() {
        reporte = mock(AiUsageReportService.class);
        when(reporte.generar(any(), any())).thenReturn(
                new AiUsageReportResponse(LocalDate.now(), LocalDate.now(), 0, BigDecimal.ZERO, List.of()));
    }

    @Test
    @DisplayName("sin llave configurada no existe, aunque manden una")
    void sinLlaveConfiguradaNoExiste() {
        // Una llave vacia en el servidor no puede significar "cualquiera
        // pasa": que alguien olvide la variable no debe abrir el reporte.
        OpsController controller = new OpsController(reporte, mock(ReconciliacionUploadPost.class), "");

        assertThatThrownBy(() -> controller.aiUsage("", null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(reporte);
    }

    @Test
    @DisplayName("con la llave equivocada, o sin ella, tampoco")
    void llaveEquivocada() {
        OpsController controller = new OpsController(reporte, mock(ReconciliacionUploadPost.class), "la-buena");

        assertThatThrownBy(() -> controller.aiUsage("la-mala", null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.aiUsage(null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(reporte);
    }

    @Test
    @DisplayName("con la llave buena y sin fechas, reporta el mes en curso")
    void llaveBuenaMesEnCurso() {
        OpsController controller = new OpsController(reporte, mock(ReconciliacionUploadPost.class), "la-buena");

        assertThat(controller.aiUsage("la-buena", null, null).getStatusCode().is2xxSuccessful()).isTrue();

        LocalDate hoy = LocalDate.now();
        verify(reporte).generar(hoy.withDayOfMonth(1), hoy);
    }

    @Test
    @DisplayName("un periodo al reves se rechaza")
    void periodoAlReves() {
        OpsController controller = new OpsController(reporte, mock(ReconciliacionUploadPost.class), "la-buena");

        assertThatThrownBy(() -> controller.aiUsage("la-buena",
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
