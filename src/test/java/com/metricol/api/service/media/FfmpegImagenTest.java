package com.metricol.api.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metricol.api.config.MediaAdaptProperties;
import com.metricol.api.enums.Platform;

/**
 * Prueba de verdad contra ffmpeg, no con un doble.
 *
 * <p>Lo que puede fallar aquí no es la lógica de Java sino la cadena de
 * filtros: un nombre de etiqueta mal escrito, un lado impar, una opción que
 * cambió de nombre entre versiones. Nada de eso se ve compilando, y con un
 * doble de prueba tampoco. Se ve cuando ffmpeg contesta con un código de error
 * y la publicación sale con la foto sin adaptar.
 *
 * <p>Si ffmpeg no está instalado la prueba se salta en vez de fallar: no tener
 * la herramienta no es un defecto del código —la aplicación está hecha para
 * seguir publicando sin ella— y una prueba en rojo por eso enseña a ignorar
 * el rojo.
 */
class FfmpegImagenTest {

    @TempDir
    Path carpeta;

    private MediaAdaptProperties props;
    private FfmpegImagen ffmpeg;

    @BeforeEach
    void preparar() {
        props = new MediaAdaptProperties();
        ffmpeg = new FfmpegImagen(props);
        assumeTrue(ffmpeg.disponible(), "ffmpeg no esta instalado en esta maquina");
    }

    @Test
    @DisplayName("una vertical 9:16 sale en el lienzo 4:5 que pide Instagram")
    void encajaUnaVerticalEnInstagram() throws IOException {
        Path origen = imagenDePrueba(1080, 1920);

        FfmpegImagen.Medidas medidas = ffmpeg.medir(origen);
        assertThat(medidas).isNotNull();
        assertThat(medidas.ancho()).isEqualTo(1080);
        assertThat(medidas.alto()).isEqualTo(1920);

        // El lienzo que calcularia el adaptador para Instagram.
        byte[] salida = ffmpeg.encajar(origen, 1440, 1800, props.getCalidad());
        assertThat(salida).isNotNull().isNotEmpty();

        Path resultado = carpeta.resolve("salida.jpg");
        Files.write(resultado, salida);

        FfmpegImagen.Medidas finales = ffmpeg.medir(resultado);
        assertThat(finales).isNotNull();
        assertThat(finales.ancho()).isEqualTo(1440);
        assertThat(finales.alto()).isEqualTo(1800);
        assertThat(EspecImagen.de(Platform.INSTAGRAM)
                .cumple(finales.ancho(), finales.alto(), salida.length)).isTrue();
    }

    @Test
    @DisplayName("una apaisada extrema tambien entra, por el otro lado")
    void encajaUnaApaisada() throws IOException {
        // 4:1 se pasa del 1.91:1 de Instagram por arriba, que es el camino
        // contrario del caso anterior y usa otra rama del calculo.
        Path origen = imagenDePrueba(2000, 500);

        byte[] salida = ffmpeg.encajar(origen, 1440, 754, props.getCalidad());
        assertThat(salida).isNotNull().isNotEmpty();

        Path resultado = carpeta.resolve("apaisada.jpg");
        Files.write(resultado, salida);
        FfmpegImagen.Medidas finales = ffmpeg.medir(resultado);
        assertThat(finales.ancho()).isEqualTo(1440);
        assertThat(finales.alto()).isEqualTo(754);
    }

    @Test
    @DisplayName("medir un archivo que no es una imagen devuelve null, no revienta")
    void medirBasuraNoRevienta() throws IOException {
        Path basura = carpeta.resolve("no-es-una-imagen.jpg");
        Files.writeString(basura, "esto no es un JPEG");
        assertThat(ffmpeg.medir(basura)).isNull();
    }

    /** Una imagen sintetica del tamaño pedido, hecha con el propio ffmpeg. */
    private Path imagenDePrueba(int ancho, int alto) throws IOException {
        Path destino = carpeta.resolve(ancho + "x" + alto + ".jpg");
        ProcessBuilder pb = new ProcessBuilder(
                props.getFfmpeg(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=" + ancho + "x" + alto,
                "-frames:v", "1", destino.toString());
        try {
            pb.start().waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertThat(Files.exists(destino)).isTrue();
        return destino;
    }
}
