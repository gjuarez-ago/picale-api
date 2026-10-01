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
    @DisplayName("si todavía no aplica (sin local, o sin lugar para sus redes) no se guarda como 'sin ubicación'")
    void todaviaNoAplica() {
        AgenteService.ConUbicacion apagada = AgenteService.ubicacionPara(conLocal(false), Set.of(Platform.INSTAGRAM), "LUGAR");
        assertThat(apagada.va()).as("nulo: si luego pone su local, esta propuesta sale con él").isNull();
        assertThat(apagada.frase()).isEmpty();
        assertThat(Ubicacion.de(conLocal(false)).alguna()).as("apagada no se manda").isFalse();

        AgenteService.ConUbicacion soloFacebook = AgenteService.ubicacionPara(conLocal(true), Set.of(Platform.FACEBOOK), "LUGAR");
        assertThat(soloFacebook.va()).isNull();
        assertThat(soloFacebook.frase()).isEmpty();

        // Lugar guardado solo en TikTok y la publicación va a Instagram: no se dice "le puse tu ubicación".
        Workspace soloTiktok = Workspace.builder().name("Tacos").ubicacionActiva(true)
                .ubicacionTiktokId("4220").ubicacionTiktokNombre("Tacos El Güero").build();
        AgenteService.ConUbicacion aInstagram = AgenteService.ubicacionPara(soloTiktok, Set.of(Platform.INSTAGRAM), "PRODUCTO");
        assertThat(aInstagram.va()).isNull();
        assertThat(aInstagram.frase()).isEmpty();
        assertThat(AgenteService.ubicacionPara(soloTiktok, Set.of(Platform.TIKTOK), "PRODUCTO").va()).isTrue();
    }

    @Test
    @DisplayName("lo que no es del local se guarda como 'sin ubicación' aunque todavía no haya local")
    void noEsDelLocal() {
        assertThat(AgenteService.ubicacionPara(conLocal(false), Set.of(Platform.INSTAGRAM), "TESTIMONIO").va()).isFalse();
    }
}
