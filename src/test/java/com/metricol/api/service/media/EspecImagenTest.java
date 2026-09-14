package com.metricol.api.service.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.enums.Platform;

/**
 * Lo que se prueba aquí es la regla que decide si una foto se toca o no. Un
 * error aquí no se ve al compilar ni al probar a mano con una foto cuadrada:
 * se ve como publicaciones que Instagram rechaza, días después y sin motivo
 * aparente.
 */
class EspecImagenTest {

    /** Una foto vertical de teléfono: 9:16. El caso que motivó todo esto. */
    private static final int VERT_ANCHO = 1080;
    private static final int VERT_ALTO = 1920;

    @Test
    @DisplayName("una vertical 9:16 no le sirve a Instagram")
    void verticalNoCabeEnInstagram() {
        EspecImagen espec = EspecImagen.de(Platform.INSTAGRAM);
        assertThat(espec.cumple(VERT_ANCHO, VERT_ALTO, 1_000_000)).isFalse();
    }

    @Test
    @DisplayName("esa misma vertical le sirve a TikTok tal cual")
    void verticalSiCabeEnTiktok() {
        EspecImagen espec = EspecImagen.de(Platform.TIKTOK);
        assertThat(espec.cumple(VERT_ANCHO, VERT_ALTO, 1_000_000)).isTrue();
    }

    @Test
    @DisplayName("publicar en TikTok e Instagram a la vez manda la regla de Instagram")
    void laInterseccionSeQuedaConLaMasEstricta() {
        // Es el corazon del diseño: una publicacion sale a varias redes en UNA
        // llamada, con un solo juego de imagenes. Si la interseccion se
        // relajara, la foto saldria bien en TikTok y rechazada en Instagram.
        EspecImagen ambas = EspecImagen.interseccion(List.of(Platform.TIKTOK, Platform.INSTAGRAM));

        assertThat(ambas.ratioMin()).isEqualTo(0.8);
        assertThat(ambas.ratioMax()).isEqualTo(1.91);
        assertThat(ambas.anchoMax()).isEqualTo(1440);
        assertThat(ambas.cumple(VERT_ANCHO, VERT_ALTO, 1_000_000)).isFalse();
    }

    @Test
    @DisplayName("el tope de peso es el mas chico de las elegidas, no el de la primera")
    void elPesoEsElMasEstricto() {
        // TikTok aguanta 10 MB e Instagram 8. Juntas mandan los 8: una foto de
        // 9 MB pasaria el filtro de TikTok y la rechazaria Instagram. TikTok va
        // primera a proposito — lo que se prueba es que gana el minimo, no el
        // orden de la lista.
        EspecImagen ambas = EspecImagen.interseccion(List.of(Platform.TIKTOK, Platform.INSTAGRAM));
        assertThat(ambas.bytesMax()).isEqualTo(8L * 1024 * 1024);
        assertThat(ambas.cumple(1080, 1080, 9L * 1024 * 1024)).isFalse();
    }

    @Test
    @DisplayName("YouTube no admite fotos, y no le estrecha los numeros a las demas")
    void youtubeNoPublicaFotosNiEstorbaALasOtras() {
        // Su tope de fotos es cero: ahi se apoya PostService para decir "solo
        // publica video" antes de subir nada.
        assertThat(EspecImagen.de(Platform.YOUTUBE).maxFotos()).isZero();

        // Y el resto de sus numeros son neutros. Si no lo fueran, marcar
        // YouTube encogeria las fotos que salen a Instagram por una red que
        // ni siquiera las acepta.
        assertThat(EspecImagen.interseccion(List.of(Platform.INSTAGRAM, Platform.YOUTUBE)))
                .usingRecursiveComparison().ignoringFields("maxFotos")
                .isEqualTo(EspecImagen.de(Platform.INSTAGRAM));
    }

    @Test
    @DisplayName("sin redes se toma la mas estricta, no la mas suelta")
    void sinRedesSeAsumeLoEstricto() {
        // Pasarse de exigente cuesta unos pixeles; quedarse corto cuesta la
        // publicacion.
        assertThat(EspecImagen.interseccion(Set.of())).isEqualTo(EspecImagen.de(Platform.INSTAGRAM));
        assertThat(EspecImagen.interseccion(null)).isEqualTo(EspecImagen.de(Platform.INSTAGRAM));
    }

    @Test
    @DisplayName("se busca la proporcion permitida MAS CERCANA, no una fija")
    void elObjetivoEsElMasCercano() {
        EspecImagen ig = EspecImagen.de(Platform.INSTAGRAM);

        // Demasiado alta -> sube al minimo. Demasiado ancha -> baja al maximo.
        assertThat(ig.ratioObjetivo(9.0 / 16)).isEqualTo(0.8);
        assertThat(ig.ratioObjetivo(3.0)).isEqualTo(1.91);
        // Y lo que ya cabia se queda donde esta: mover una foto que servia
        // seria estropearla por nada.
        assertThat(ig.ratioObjetivo(1.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("una foto enorme que cumple la proporcion tampoco pasa")
    void elAnchoTambienCuenta() {
        EspecImagen ig = EspecImagen.de(Platform.INSTAGRAM);
        assertThat(ig.cumple(4000, 4000, 1_000_000)).isFalse();
        assertThat(ig.cumple(1440, 1440, 1_000_000)).isTrue();
    }
}
