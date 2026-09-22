package com.metricol.api.service.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

import com.metricol.api.config.UploadPostProperties;

/**
 * Qué texto le llega a cada red en una publicación de fotos.
 *
 * <p>Sale de un fallo real (22 sep 2026): la persona aprobó un texto para
 * Facebook en la vista previa y Facebook publicó el de Instagram. En
 * {@code /upload_photos} el texto visible de Facebook es el {@code description}
 * general, no {@code facebook_title}, y ahí iba el texto común.
 */
class UploadPostClientTextosTest {

    private static final String COMUN = "Texto de Instagram, el primero elegido";
    private static final String DE_FACEBOOK = "Texto aprobado para Facebook";
    private static final String DE_TIKTOK = "Texto corto TikTok";

    private UploadPostClient cliente() {
        UploadPostProperties props = new UploadPostProperties();
        props.setBaseUrl("http://localhost");
        props.setApiKey("prueba");
        return new UploadPostClient(props);
    }

    @Test
    void facebookRecibeSuPropioTextoEnDescription() {
        MultiValueMap<String, Object> body = cliente().cuerpoFotos(
                "perfil", List.of("instagram", "facebook"), COMUN,
                Map.of("INSTAGRAM", COMUN, "FACEBOOK", DE_FACEBOOK), null);

        assertThat(body.getFirst("title")).isEqualTo(COMUN);
        assertThat(body.getFirst("description")).isEqualTo(DE_FACEBOOK);
        assertThat(body.getFirst("facebook_title")).isEqualTo(DE_FACEBOOK);
        assertThat(body.getFirst("instagram_title")).isEqualTo(COMUN);
    }

    @Test
    void elTextoDeFacebookNoSeCuelaEnTiktokNiLinkedin() {
        MultiValueMap<String, Object> body = cliente().cuerpoFotos(
                "perfil", List.of("tiktok", "linkedin", "facebook"), COMUN,
                Map.of("FACEBOOK", DE_FACEBOOK, "TIKTOK", DE_TIKTOK), null);

        assertThat(body.getFirst("description")).isEqualTo(DE_FACEBOOK);
        // TikTok trae el suyo: va en title y en description de fotos.
        assertThat(body.getFirst("tiktok_title")).isEqualTo(DE_TIKTOK);
        assertThat(body.getFirst("tiktok_description")).isEqualTo(DE_TIKTOK);
        // LinkedIn no trae propio: recibe el común en su description, no el de Facebook.
        assertThat(body.getFirst("linkedin_description")).isEqualTo(COMUN);
        assertThat(body.get("linkedin_title")).isNull();
    }

    @Test
    void sinTextoPropioDeFacebookTodoSigueComoAntes() {
        MultiValueMap<String, Object> body = cliente().cuerpoFotos(
                "perfil", List.of("instagram", "facebook"), COMUN, Map.of(), null);

        assertThat(body.getFirst("title")).isEqualTo(COMUN);
        assertThat(body.getFirst("description")).isEqualTo(COMUN);
        assertThat(body.get("facebook_title")).isNull();
    }
}
