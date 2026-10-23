package com.metricol.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.AiOperacion;

/** Las descripciones pueden ir con un modelo más capaz sin cambiar el resto de la IA. */
class RedactorPropioTest {

    @Test
    @DisplayName("solo redactar, retocar y acortar usan el modelo del redactor; sin configurarlo, nadie")
    void soloLaRedaccion() {
        OpenAiProperties props = new OpenAiProperties();
        assertThat(props.usaRedactor(AiOperacion.REDACTAR)).as("sin modelo propio").isFalse();

        props.setRedactorModel("gpt-6.1-sol");
        assertThat(props.usaRedactor(AiOperacion.REDACTAR)).isTrue();
        assertThat(props.usaRedactor(AiOperacion.AJUSTAR)).isTrue();
        assertThat(props.usaRedactor(AiOperacion.ACORTAR)).isTrue();
        assertThat(props.usaRedactor(AiOperacion.AGENTE_TRANSCRIBIR)).isFalse();
        assertThat(props.getRedactorReasoningEffort()).isEqualTo("medium");
    }
}
