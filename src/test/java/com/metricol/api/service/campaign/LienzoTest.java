package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Platform;
import com.metricol.api.service.campaign.CampaignImageService.Formato;
import com.metricol.api.service.campaign.Lienzo.Variante;

class LienzoTest {

    @Test
    @DisplayName("Instagram y Facebook comparten imagen en una publicación; LinkedIn tiene la suya cuadrada")
    void agrupaPorLienzo() {
        List<Variante> variantes = Variante.agrupar(Formato.POST,
                List.of(Platform.INSTAGRAM, Platform.FACEBOOK, Platform.LINKEDIN));

        assertThat(variantes).hasSize(2);
        assertThat(variantes.get(0).lienzo()).isEqualTo(Lienzo.CUATRO_QUINTOS);
        assertThat(variantes.get(0).redes()).containsExactly(Platform.INSTAGRAM, Platform.FACEBOOK);
        assertThat(variantes.get(1).lienzo()).isEqualTo(Lienzo.CUADRADO);
        assertThat(variantes.get(1).redes()).containsExactly(Platform.LINKEDIN);
        assertThat(variantes).extracting(Variante::id).containsExactly("v1", "v2");
    }

    @Test
    @DisplayName("una sola red da una sola versión")
    void unaRed() {
        List<Variante> variantes = Variante.agrupar(Formato.POST, List.of(Platform.LINKEDIN));

        assertThat(variantes).hasSize(1);
        assertThat(variantes.get(0).lienzo()).isEqualTo(Lienzo.CUADRADO);
    }

    @Test
    @DisplayName("en una historia todas van a 9:16 y comparten imagen")
    void historia() {
        List<Variante> variantes = Variante.agrupar(Formato.STORY, List.of(Platform.INSTAGRAM, Platform.FACEBOOK));

        assertThat(variantes).hasSize(1);
        assertThat(variantes.get(0).lienzo()).isEqualTo(Lienzo.HISTORIA);
        assertThat(variantes.get(0).lienzo().historia()).isTrue();
    }

    @Test
    @DisplayName("el orden en que se eligieron las redes decide cuál versión va primera")
    void ordenDeEleccion() {
        List<Variante> variantes = Variante.agrupar(Formato.POST,
                List.of(Platform.LINKEDIN, Platform.INSTAGRAM, Platform.FACEBOOK));

        assertThat(variantes.get(0).lienzo()).isEqualTo(Lienzo.CUADRADO);
        assertThat(variantes.get(1).redes()).containsExactly(Platform.INSTAGRAM, Platform.FACEBOOK);
    }

    @Test
    @DisplayName("el carrusel se agrupa igual que la publicación")
    void carrusel() {
        List<Variante> variantes = Variante.agrupar(Formato.CAROUSEL,
                List.of(Platform.INSTAGRAM, Platform.LINKEDIN));

        assertThat(variantes).hasSize(2);
    }

    @Test
    @DisplayName("los lienzos piden a la IA solo tamaños que gpt-image admite")
    void tamanos() {
        assertThat(Lienzo.CUADRADO.tamano).isEqualTo("1024x1024");
        assertThat(Lienzo.CUATRO_QUINTOS.tamano).isEqualTo("1024x1536");
        assertThat(Lienzo.HISTORIA.tamano).isEqualTo("1024x1536");
        assertThat(Lienzo.CUATRO_QUINTOS.etiqueta).isEqualTo("4:5");
    }
}
