package com.metricol.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La versión mínima de la app que se deja usar. La app lee
 * {@code result.androidMinima}: si cambia el nombre, deja de obligar sin que
 * nada falle.
 */
class AppVersionControllerTest {

    @Test
    @DisplayName("contesta la mínima configurada con el nombre que lee la app")
    void contestaLaMinima() {
        Map<String, Integer> respuesta = new AppVersionController(15).version().getBody().getResult();

        assertThat(respuesta).containsExactly(Map.entry("androidMinima", 15));
    }

    @Test
    @DisplayName("un número negativo por error no obliga a nadie")
    void negativoEsCero() {
        assertThat(new AppVersionController(-3).version().getBody().getResult())
                .containsEntry("androidMinima", 0);
    }
}
