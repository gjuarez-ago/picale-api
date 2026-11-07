package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.service.ai.OpenAiClient;

class ComparadorDeFotosTest {

    private final OpenAiClient client = mock(OpenAiClient.class);
    private final ComparadorDeFotos comparador = new ComparadorDeFotos(client);

    @Test
    @DisplayName("lee el veredicto aunque venga envuelto en texto o como cadena")
    void interpreta() throws Exception {
        assertThat(comparador.interpretar("```json\n{\"misma\": true, \"diferencia\": \"\"}\n```")).isTrue();
        assertThat(comparador.interpretar("{\"misma\": false, \"diferencia\": \"otro precio\"}")).isFalse();
        assertThat(comparador.interpretar("{\"misma\": \"true\"}")).isTrue();
        assertThat(comparador.interpretar("no sé")).isFalse();
        assertThat(comparador.interpretar(null)).isFalse();
    }

    @Test
    @DisplayName("si la IA falla, no son la misma: mejor una repetida que una distinta perdida")
    void anteLaFallaNoDescarta() {
        when(client.describeImages(any(), anyString(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("sin red"));

        assertThat(comparador.misma("https://a/1.jpg", "https://a/2.jpg")).isFalse();
    }
}
