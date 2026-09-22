package com.metricol.api.service.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

import com.metricol.api.config.UploadPostProperties;
import com.metricol.api.enums.PostFormat;

/**
 * Qué texto le llega a cada red, y en qué campo.
 *
 * <p>Sale de un fallo real (22 sep 2026): la persona aprobó un texto para
 * Facebook en la vista previa y Facebook publicó el de Instagram, cortado a
 * 90 con puntos suspensivos. En {@code /upload_photos} el texto visible de
 * Facebook es el {@code description} general, no {@code facebook_title}, y
 * ahí iba el texto común recortado a la red más estrecha.
 *
 * <p>Desde entonces viajan dos textos: un título corto común y el caption de
 * cada red, cada uno en el campo que esa red muestra.
 */
class UploadPostClientTextosTest {

    private static final String TITULO = "Mallas ciclónicas para predios industriales";
    private static final String DE_INSTAGRAM = "Texto de Instagram, el primero elegido ✨ #Seguridad";
    private static final String DE_FACEBOOK = "Texto aprobado para Facebook, de 238 caracteres";
    private static final String DE_TIKTOK = "Texto de TikTok";
    private static final String DE_LINKEDIN = "Texto de LinkedIn, largo y profesional";

    private static final Map<String, String> TODOS = Map.of(
            "INSTAGRAM", DE_INSTAGRAM,
            "FACEBOOK", DE_FACEBOOK,
            "TIKTOK", DE_TIKTOK,
            "LINKEDIN", DE_LINKEDIN);

    private static final List<String> REDES = List.of("tiktok", "instagram", "linkedin", "facebook");

    private UploadPostClient cliente() {
        UploadPostProperties props = new UploadPostProperties();
        props.setBaseUrl("http://localhost");
        props.setApiKey("prueba");
        return new UploadPostClient(props);
    }

    @Test
    @DisplayName("fotos: Facebook recibe su caption en description y el título en facebook_title")
    void fotosFacebook() {
        MultiValueMap<String, Object> body = cliente().cuerpoFotos("perfil", REDES, TITULO, TODOS, null);

        assertThat(body.getFirst("title")).isEqualTo(TITULO);
        assertThat(body.getFirst("description")).isEqualTo(DE_FACEBOOK);
        assertThat(body.getFirst("facebook_title")).isEqualTo(TITULO);
        // Para fotos no existe el campo: mandarlo no sirve y confunde.
        assertThat(body.get("facebook_description")).isNull();
    }

    @Test
    @DisplayName("fotos: Instagram recibe su caption; TikTok y LinkedIn título y caption aparte")
    void fotosDemasRedes() {
        MultiValueMap<String, Object> body = cliente().cuerpoFotos("perfil", REDES, TITULO, TODOS, null);

        assertThat(body.getFirst("instagram_title")).isEqualTo(DE_INSTAGRAM);

        assertThat(body.getFirst("tiktok_title")).isEqualTo(TITULO);
        assertThat(body.getFirst("tiktok_description")).isEqualTo(DE_TIKTOK);

        assertThat(body.getFirst("linkedin_title")).isEqualTo(TITULO);
        assertThat(body.getFirst("linkedin_description")).isEqualTo(DE_LINKEDIN);
    }

    @Test
    @DisplayName("video: Facebook lleva título y caption aparte; TikTok su caption en tiktok_title")
    void video() {
        MultiValueMap<String, Object> body = cliente().cuerpoVideo("perfil", REDES, TITULO, TODOS, PostFormat.REEL);

        assertThat(body.getFirst("title")).isEqualTo(TITULO);
        assertThat(body.getFirst("facebook_title")).isEqualTo(TITULO);
        assertThat(body.getFirst("facebook_description")).isEqualTo(DE_FACEBOOK);
        // En video TikTok solo tiene un texto visible, y admite 2200: va el caption.
        assertThat(body.getFirst("tiktok_title")).isEqualTo(DE_TIKTOK);
        assertThat(body.get("tiktok_description")).isNull();
        assertThat(body.getFirst("instagram_title")).isEqualTo(DE_INSTAGRAM);
        assertThat(body.getFirst("linkedin_description")).isEqualTo(DE_LINKEDIN);
    }

    @Test
    @DisplayName("el caption de Facebook no se cuela en TikTok ni LinkedIn cuando no traen el suyo")
    void facebookNoSeCuela() {
        MultiValueMap<String, Object> body = cliente().cuerpoFotos(
                "perfil", List.of("tiktok", "linkedin", "facebook"), TITULO,
                Map.of("FACEBOOK", DE_FACEBOOK, "TIKTOK", DE_TIKTOK), null);

        assertThat(body.getFirst("description")).isEqualTo(DE_FACEBOOK);
        // LinkedIn no trae propio: recibe el primer caption disponible, no el de Facebook por accidente.
        assertThat(body.getFirst("linkedin_description")).isNotNull().isNotEqualTo("");
        assertThat(body.getFirst("tiktok_description")).isEqualTo(DE_TIKTOK);
    }

    @Test
    @DisplayName("sin título, el primer caption hace de título para no publicar sin `title`")
    void sinTitulo() {
        // En orden, como los arma el publisher: el primero es el de la primera red.
        Map<String, String> enOrden = new java.util.LinkedHashMap<>();
        enOrden.put("INSTAGRAM", DE_INSTAGRAM);
        enOrden.put("FACEBOOK", DE_FACEBOOK);
        MultiValueMap<String, Object> body = cliente().cuerpoFotos(
                "perfil", List.of("instagram", "facebook"), null, enOrden, null);

        assertThat(body.getFirst("title")).isEqualTo(DE_INSTAGRAM);
        assertThat(body.getFirst("description")).isEqualTo(DE_FACEBOOK);
    }

    @Test
    @DisplayName("una historia marca el formato en los dos campos, con sus nombres asimétricos")
    void historia() {
        MultiValueMap<String, Object> body = cliente().cuerpoVideo(
                "perfil", List.of("instagram", "facebook"), TITULO, TODOS, PostFormat.STORY);

        assertThat(body.getFirst("media_type")).isEqualTo("STORIES");
        assertThat(body.getFirst("facebook_media_type")).isEqualTo("STORIES");
    }
}
