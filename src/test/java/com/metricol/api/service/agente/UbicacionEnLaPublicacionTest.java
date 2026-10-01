package com.metricol.api.service.agente;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Platform;
import com.metricol.api.service.social.Ubicacion;

/** Cuándo una publicación del agente sale con la ubicación del negocio. */
class UbicacionEnLaPublicacionTest {

    private static Workspace conLocal(boolean activa) {
        return Workspace.builder().name("Tacos").ubicacionActiva(activa).ubicacionInstagramId("213385402").build();
    }

    @Test
    @DisplayName("va cuando lo que se ve es del negocio; no en recorridos, testimonios ni lo que no se reconoce")
    void porTipo() {
        assertThat(UbicacionEnLaPublicacion.va("LUGAR")).isTrue();
        assertThat(UbicacionEnLaPublicacion.va("producto")).isTrue();
        assertThat(UbicacionEnLaPublicacion.va("EVENTO")).isTrue();
        assertThat(UbicacionEnLaPublicacion.va("RECORRIDO")).as("en una inmobiliaria es la propiedad").isFalse();
        assertThat(UbicacionEnLaPublicacion.va("TESTIMONIO")).isFalse();
        assertThat(UbicacionEnLaPublicacion.va("OTRO")).isFalse();
        assertThat(UbicacionEnLaPublicacion.va(null)).isFalse();
    }

    @Test
    @DisplayName("con local y una red que la admite, la pone y lo dice; si no es del local, no la pone y lo dice")
    void conLocal() {
        AgenteService.ConUbicacion foto = AgenteService.ubicacionPara(conLocal(true), Set.of(Platform.INSTAGRAM), "PRODUCTO");
        assertThat(foto.va()).isTrue();
        assertThat(foto.frase()).contains("ubicación");

        AgenteService.ConUbicacion frase = AgenteService.ubicacionPara(conLocal(true), Set.of(Platform.INSTAGRAM), "OTRO");
        assertThat(frase.va()).isFalse();
        assertThat(frase.frase()).contains("Sin ubicación");
    }

    @Test
    @DisplayName("sin local, o solo a redes que no la admiten, ni la pone ni dice nada")
    void sinLocal() {
        AgenteService.ConUbicacion apagada = AgenteService.ubicacionPara(conLocal(false), Set.of(Platform.INSTAGRAM), "LUGAR");
        assertThat(apagada.va()).isFalse();
        assertThat(apagada.frase()).isEmpty();
        assertThat(Ubicacion.de(conLocal(false)).alguna()).as("apagada no se manda").isFalse();

        AgenteService.ConUbicacion soloFacebook = AgenteService.ubicacionPara(conLocal(true), Set.of(Platform.FACEBOOK), "LUGAR");
        assertThat(soloFacebook.va()).isFalse();
        assertThat(soloFacebook.frase()).isEmpty();
    }
}
