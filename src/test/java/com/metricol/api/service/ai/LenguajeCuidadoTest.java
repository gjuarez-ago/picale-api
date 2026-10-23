package com.metricol.api.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LenguajeCuidadoTest {

    @Test
    @DisplayName("detecta las groserías comunes de México, con o sin mayúsculas y acentos")
    void detecta() {
        for (String frase : new String[] {"Está bien chingón", "qué pendejada", "PINCHE precio", "vale madre, mierda",
                "no seas cabrón", "cabrona la oferta", "ptm qué rico", "this is shit", "hijo de puta", "está de huevones"}) {
            assertThat(LenguajeCuidado.tieneGroserias(frase)).as(frase).isTrue();
        }
    }

    @Test
    @DisplayName("no confunde palabras normales: cono, recoger, computadora, pinche de cocina, disputa")
    void sinFalsosPositivos() {
        for (String frase : new String[] {"Helado en cono de chocolate", "Pasa a recoger tu pedido", "Reparamos tu computadora",
                "Buscamos pinche de cocina", "Una disputa amistosa", "Cuidamos tu culata de aluminio", "Escuela de computo",
                "Chinguirito no, aguas frescas sí", "Huevos rancheros"}) {
            assertThat(LenguajeCuidado.tieneGroserias(frase)).as(frase).isFalse();
        }
    }

    @Test
    @DisplayName("tapa la grosería dejando su primera letra y el resto igual")
    void cubre() {
        assertThat(LenguajeCuidado.cubrir("Está bien chingón el pastel")).isEqualTo("Está bien c****** el pastel");
        assertThat(LenguajeCuidado.cubrir("Todo en orden")).isEqualTo("Todo en orden");
    }
}
