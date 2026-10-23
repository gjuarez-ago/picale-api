package com.metricol.api.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.metricol.api.config.MediaAdaptProperties;

/**
 * Que la portada deje de salir del segundo uno a ciegas.
 *
 * <p>Se puntúa con imágenes de verdad —un JPEG negro, uno plano y uno con
 * detalle— y no con números inventados: el criterio es la nitidez que mide
 * {@link HuellaDeImagen}, y una prueba con valores de mentira no diría si ese
 * criterio distingue lo que tiene que distinguir.
 */
class MejorCuadroDeVideoTest {

    private final MediaAdaptProperties props = new MediaAdaptProperties();
    private final FfmpegImagen ffmpeg = mock(FfmpegImagen.class);
    private final MejorCuadroDeVideo mejor = new MejorCuadroDeVideo(props, ffmpeg);
    private final Path video = Path.of("video.mp4");

    /** Un cuadro negro: lo que sale cuando el sensor no habia ajustado la luz. */
    private static byte[] negro() throws Exception {
        return jpeg(relleno(0));
    }

    /** Gris plano: hay luz, pero no hay nada que ver. Nitidez casi cero. */
    private static byte[] plano() throws Exception {
        return jpeg(relleno(128));
    }

    /** Con detalle de verdad: es el que tiene que ganar. */
    private static byte[] conDetalle() throws Exception {
        BufferedImage imagen = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                boolean claro = ((x / 2) + (y / 2)) % 2 == 0;
                imagen.setRGB(x, y, claro ? 0xFFFFFF : 0x000000);
            }
        }
        return jpeg(imagen);
    }

    private static BufferedImage relleno(int gris) {
        BufferedImage imagen = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        int rgb = (gris << 16) | (gris << 8) | gris;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                imagen.setRGB(x, y, rgb);
            }
        }
        return imagen;
    }

    private static byte[] jpeg(BufferedImage imagen) throws Exception {
        var salida = new ByteArrayOutputStream();
        ImageIO.write(imagen, "jpg", salida);
        return salida.toByteArray();
    }

    private void cuadro(String segundo, byte[] jpeg) {
        when(ffmpeg.fotogramaExacto(eq(video), eq(segundo), anyInt(), anyInt())).thenReturn(jpeg);
    }

    @Test
    void eligeElCuadroConImagenYNoElPrimero() throws Exception {
        cuadro("1.000", negro());
        cuadro("2.000", plano());
        cuadro("3.000", conDetalle());
        cuadro("4.000", plano());
        cuadro("5.000", plano());

        assertThat(mejor.segundoDe(video, null)).isEqualTo(3);
    }

    /**
     * Un cuadro negro puede puntuar alto en nitidez por el grano del sensor y
     * seguir siendo inservible. Se descarta por luz antes de medirlo.
     */
    @Test
    void descartaElNegroAunqueSeaElUnicoConGrano() throws Exception {
        cuadro("1.000", negro());
        cuadro("2.000", plano());

        // Gana el plano: feo, pero se ve algo.
        assertThat(mejor.segundoDe(video, null)).isEqualTo(2);
    }

    /** Sin ningun cuadro servible se queda en el segundo uno, como antes. */
    @Test
    void sinNadaServibleVuelveAlSegundoUno() {
        assertThat(mejor.segundoDe(video, null)).isEqualTo(MejorCuadroDeVideo.POR_OMISION);
    }

    /**
     * Un video de dos segundos no tiene segundo tres. Pedirselo a ffmpeg es
     * trabajo tirado y, peor, invita a confundir el cuadro cero con ese.
     */
    @Test
    void noPruebaSegundosQueElVideoNoTiene() throws Exception {
        cuadro("1.000", conDetalle());

        assertThat(mejor.segundoDe(video, 2)).isEqualTo(1);
        verify(ffmpeg, never()).fotogramaExacto(eq(video), eq("2.000"), anyInt(), anyInt());
        verify(ffmpeg, never()).fotogramaExacto(eq(video), eq("3.000"), anyInt(), anyInt());
    }

    /** Con ffmpeg apagado no se intenta nada y se contesta lo de siempre. */
    @Test
    void conFfmpegApagadoNoTocaElVideo() {
        props.setEnabled(false);

        assertThat(mejor.segundoDe(video, null)).isEqualTo(MejorCuadroDeVideo.POR_OMISION);
        verify(ffmpeg, never()).fotogramaExacto(eq(video), org.mockito.ArgumentMatchers.anyString(),
                anyInt(), anyInt());
    }
}
