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

    @Test
    @DisplayName("lee la respuesta real del buscador de lugares de TikTok (location_id, location_name, location_address)")
    void lugaresDeTiktok() {
        java.util.Map<String, Object> real = java.util.Map.of("success", true, "query", "Merida Yucatan",
                "locations", List.of(
                        java.util.Map.of("location_address", "C. 50, Centro, 97000 Mérida, Yuc., Mexico",
                                "location_id", "42203861124285763", "location_name", "Mérida Yucatán"),
                        java.util.Map.of("location_id", "1", "location_address", "sin nombre")));
        List<UploadPostClient.LugarTiktok> lugares = UploadPostClient.lugaresDe(real);
        assertThat(lugares).hasSize(1);
        assertThat(lugares.get(0).id()).isEqualTo("42203861124285763");
        assertThat(lugares.get(0).nombre()).isEqualTo("Mérida Yucatán");
        assertThat(lugares.get(0).direccion()).contains("Centro");
        assertThat(UploadPostClient.lugaresDe(java.util.Map.of("success", false, "message", "profile is required"))).isEmpty();
        assertThat(UploadPostClient.lugaresDe(null)).isEmpty();
    }
}
