package com.metricol.api.service.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.metricol.api.config.UploadPostProperties;

/** La ubicación del negocio, en el campo que lee cada red. */
class UbicacionTest {

    private UploadPostClient cliente() {
        UploadPostProperties props = new UploadPostProperties();
        props.setBaseUrl("http://localhost");
        props.setApiKey("prueba");
        return new UploadPostClient(props);
    }

    @Test
    @DisplayName("el id de Instagram sale del enlace que se copia de la app, o del número solo")
    void idDeInstagram() {
        assertThat(Ubicacion.idDeInstagram("https://www.instagram.com/explore/locations/213385402/merida-yucatan/"))
                .isEqualTo("213385402");
        assertThat(Ubicacion.idDeInstagram(" 213385402 ")).isEqualTo("213385402");
        assertThat(Ubicacion.idDeInstagram("Mérida, Yucatán")).isNull();
        assertThat(Ubicacion.idDeInstagram(null)).isNull();
    }

    @Test
    @DisplayName("Instagram recibe location_id y TikTok su id con su nombre; solo las redes del envío")
    void enElEnvio() {
        Ubicacion u = new Ubicacion("213385402", "6789", "Tacos El Güero");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        cliente().ubicacion(body, List.of("instagram", "tiktok", "facebook"), u);
        assertThat(body.getFirst("location_id")).isEqualTo("213385402");
        assertThat(body.getFirst("tiktok_location_id")).isEqualTo("6789");
        assertThat(body.getFirst("tiktok_location_name")).isEqualTo("Tacos El Güero");

        MultiValueMap<String, Object> soloFacebook = new LinkedMultiValueMap<>();
        cliente().ubicacion(soloFacebook, List.of("facebook"), u);
        assertThat(soloFacebook).isEmpty();
    }

    @Test
    @DisplayName("TikTok con id pero sin nombre no se manda: lo rechazaría")
    void tiktokIncompleto() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        cliente().ubicacion(body, List.of("tiktok"), new Ubicacion(null, "6789", null));
        assertThat(body).isEmpty();
        cliente().ubicacion(body, List.of("instagram"), Ubicacion.NINGUNA);
        assertThat(body).isEmpty();
    }
}
