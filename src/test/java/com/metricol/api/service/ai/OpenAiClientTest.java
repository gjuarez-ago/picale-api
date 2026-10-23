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

    @Test
    @DisplayName("a un modelo que razona no se le manda temperatura: la rechaza con un 400")
    void temperaturaSoloDondeSeAdmite() {
        assertThat(OpenAiClient.admiteTemperatura("gpt-4.1-mini")).isTrue();
        assertThat(OpenAiClient.admiteTemperatura("gpt-4o")).isTrue();
        assertThat(OpenAiClient.admiteTemperatura("gpt-5-mini")).isFalse();
        assertThat(OpenAiClient.admiteTemperatura("GPT-5.1-mini")).isFalse();
        assertThat(OpenAiClient.admiteTemperatura("o3-mini")).isFalse();
        // Sin modelo configurado se manda como siempre: no se rompe nada.
        assertThat(OpenAiClient.admiteTemperatura(null)).isTrue();
    }

    @Test
    @DisplayName("un modelo nuevo se trata como los que razonan, no al reves")
    void losModelosNuevosNoRecibenTemperatura() {
        // Comprobado contra la API: los dos rechazan temperature con un 400.
        assertThat(OpenAiClient.admiteTemperatura("gpt-6-luna")).isFalse();
        assertThat(OpenAiClient.admiteTemperatura("gpt-6.1-sol")).isFalse();
        // Y el que todavia no existe tambien, que es el punto de la regla.
        assertThat(OpenAiClient.admiteTemperatura("gpt-7-loquesea")).isFalse();
    }
}
