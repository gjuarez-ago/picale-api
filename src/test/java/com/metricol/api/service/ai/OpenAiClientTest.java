package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lectura de los tokens que OpenAI dice haber cobrado en cada respuesta. */
class OpenAiClientTest {

    @Test
    @DisplayName("lee los tokens de entrada y de salida del bloque usage")
    void leeElUso() {
        Map<String, Object> respuesta = Map.of(
                "model", "gpt-4.1-mini-2025-04-14",
                "usage", Map.of("prompt_tokens", 912, "completion_tokens", 704, "total_tokens", 1616));

        OpenAiClient.Uso uso = OpenAiClient.Uso.de(respuesta);

        assertThat(uso.entrada()).isEqualTo(912);
        assertThat(uso.salida()).isEqualTo(704);
    }

    @Test
    @DisplayName("sin bloque usage no revienta: cuenta cero")
    void sinUsoCuentaCero() {
        // Una respuesta rara no puede tumbar el texto que ya se escribio solo
        // porque no se pudo contar.
        assertThat(OpenAiClient.Uso.de(Map.of("choices", List.of()))).isEqualTo(new OpenAiClient.Uso(0, 0));
        assertThat(OpenAiClient.Uso.de(null)).isEqualTo(new OpenAiClient.Uso(0, 0));
    }
}
