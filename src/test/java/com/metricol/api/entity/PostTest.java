package com.metricol.api.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.PostFormat;

/**
 * De {@code esVideo} depende a qué endpoint del proveedor va la publicación,
 * así que un fallo aquí manda un video al endpoint de fotos y la red lo
 * rechaza con un error que no explica nada.
 */
class PostTest {

    private static Post con(MediaType tipo, String... urls) {
        return Post.builder()
                .mediaUrls(new java.util.ArrayList<>(List.of(urls)))
                .mediaType(tipo)
                .build();
    }

    @Test
    @DisplayName("Manda el tipo guardado, no la extensión de la URL")
    void elTipoGuardadoManda() {
        // El caso que la extensión no sabía resolver: un video servido desde
        // una URL que no acaba en .mp4.
        assertThat(con(MediaType.VIDEO, "https://cdn.x/abc?v=1").esVideo()).isTrue();

        // Y el contrario: una foto cuyo nombre termina pareciendo un video.
        assertThat(con(MediaType.IMAGE, "https://cdn.x/captura-de-un.mp4.jpg").esVideo()).isFalse();
    }

    @Test
    @DisplayName("Sin tipo guardado vuelve a la extensión, como antes")
    void sinTipoVuelveALaExtension() {
        // Las filas anteriores a la columna. Aquí la extensión no es una
        // aproximación: es exactamente lo que decidió a qué endpoint fueron
        // cuando se publicaron.
        assertThat(con(null, "https://cdn.x/clip.mp4").esVideo()).isTrue();
        assertThat(con(null, "https://cdn.x/clip.MOV").esVideo()).isTrue();
        assertThat(con(null, "https://cdn.x/foto.jpg").esVideo()).isFalse();
    }

    @Test
    @DisplayName("Una publicación sin medios no es un video")
    void sinMediosNoEsVideo() {
        assertThat(Post.builder().build().esVideo()).isFalse();
        assertThat(Post.esVideoPorExtension(null)).isFalse();
    }

    @Test
    @DisplayName("El formato elegido manda sobre lo que parezca el archivo")
    void elFormatoElegidoManda() {
        Post historia = con(MediaType.VIDEO, "https://cdn.x/clip.mp4");
        historia.setFormat(PostFormat.STORY);

        // El mismo video sirve para un reel o para una historia: lo decide
        // quien publica, no el archivo. Si esto se rompe, una historia sale
        // publicada como reel y se queda en el perfil para siempre.
        assertThat(historia.formatoEfectivo()).isEqualTo(PostFormat.STORY);
    }

    @Test
    @DisplayName("Sin formato guardado se deduce del archivo, y nunca es historia")
    void sinFormatoSeDeduce() {
        // Las publicaciones anteriores a la columna, y los clientes que
        // todavia no mandan el formato. Salen como salian antes.
        assertThat(con(MediaType.VIDEO, "https://cdn.x/clip.mp4").formatoEfectivo())
                .isEqualTo(PostFormat.REEL);
        assertThat(con(MediaType.IMAGE, "https://cdn.x/foto.jpg").formatoEfectivo())
                .isEqualTo(PostFormat.PHOTO);

        // Nunca una historia: no hay nada en el archivo que permita
        // adivinarla, y suponerla convertiria una publicacion normal en algo
        // que desaparece a las veinticuatro horas.
        for (MediaType tipo : MediaType.values()) {
            assertThat(con(tipo, "https://cdn.x/medio").formatoEfectivo())
                    .isNotEqualTo(PostFormat.STORY);
        }
    }
}
