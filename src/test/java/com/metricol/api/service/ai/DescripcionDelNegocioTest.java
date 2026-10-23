package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.metricol.api.enums.AiOperacion;

class DescripcionDelNegocioTest {

    @Test
    void usaElGiroYComplementaLoQueLaPersonaYaEscribio() {
        OpenAiClient client = mock(OpenAiClient.class);
        when(client.complete(eq(AiOperacion.SUGERIR_DESCRIPCION_NEGOCIO), eq(DescripcionDelNegocio.SISTEMA),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("\"Damos mantenimiento preventivo y correctivo a equipos industriales.\"");

        String descripcion = new DescripcionDelNegocio(client).sugerir(
                "Tecno Sur", "Mantenimiento industrial", "Mérida, Yucatán", "Reparamos maquinaria");

        assertThat(descripcion).isEqualTo("Damos mantenimiento preventivo y correctivo a equipos industriales.");
        ArgumentCaptor<String> pedido = ArgumentCaptor.forClass(String.class);
        verify(client).complete(eq(AiOperacion.SUGERIR_DESCRIPCION_NEGOCIO),
                eq(DescripcionDelNegocio.SISTEMA), pedido.capture());
        assertThat(pedido.getValue())
                .contains("Tecno Sur", "Mantenimiento industrial", "Mérida, Yucatán", "Reparamos maquinaria")
                .contains("sin agregar datos que no estén confirmados");
    }

    @Test
    void limpiaSaltosYRespetaElLimiteDelFormulario() {
        assertThat(DescripcionDelNegocio.limpiar("Primera línea.\nSegunda línea."))
                .isEqualTo("Primera línea. Segunda línea.");
        assertThat(DescripcionDelNegocio.limpiar("x".repeat(700))).hasSize(500);
    }
}
