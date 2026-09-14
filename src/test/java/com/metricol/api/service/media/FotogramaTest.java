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

/**
 * La miniatura de un video, contra ffmpeg de verdad.
 *
 * <p>Lo que puede fallar aquí no es la lógica de Java: es el orden de los
 * argumentos de ffmpeg. {@code -ss} antes de {@code -i} salta directo al
 * punto; después, decodifica el video entero para llegar ahí. Las dos formas
 * dan la misma imagen, así que un error no se ve mirando el resultado — se ve
 * como una galería que tarda, meses después.
 */
class FotogramaTest {

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
    @DisplayName("de un video sale un JPEG del ancho pedido")
    void sacaLaMiniatura() throws IOException {
        Path video = videoDePrueba(5, 1280, 720);

        byte[] imagen = ffmpeg.fotograma(video, "1", 480, props.getCalidad());
        assertThat(imagen).isNotNull().isNotEmpty();

        Path salida = carpeta.resolve("mini.jpg");
        Files.write(salida, imagen);

        FfmpegImagen.Medidas medidas = ffmpeg.medir(salida);
        assertThat(medidas).isNotNull();
        assertThat(medidas.ancho()).isEqualTo(480);
        // 1280x720 a 480 de ancho son 270 de alto, redondeado a par.
        assertThat(medidas.alto()).isEqualTo(270);
    }

    @Test
    @DisplayName("un video mas corto que el segundo pedido tambien da miniatura")
    void elVideoCortoNoSeQuedaSinMiniatura() throws IOException {
        // Se pide el segundo 1 de un video que dura medio segundo: ahi no hay
        // nada, y sin el reintento desde cero saldria vacio. Es raro, pero un
        // video de medio segundo existe y quedarse sin miniatura por eso seria
        // tonto.
        Path video = videoDePrueba(1, 640, 480);

        byte[] imagen = ffmpeg.fotograma(video, "30", 320, props.getCalidad());
        assertThat(imagen).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("un archivo que no es video devuelve null, no revienta")
    void laBasuraNoRevienta() throws IOException {
        Path basura = carpeta.resolve("no-es-video.mp4");
        Files.writeString(basura, "esto no es un video");
        assertThat(ffmpeg.fotograma(basura, "1", 480, 3)).isNull();
    }

    /** Un video sintetico, hecho con el propio ffmpeg. */
    private Path videoDePrueba(int segundos, int ancho, int alto) throws IOException {
        Path destino = carpeta.resolve("prueba.mp4");
        ProcessBuilder pb = new ProcessBuilder(
                props.getFfmpeg(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=" + ancho + "x" + alto + ":rate=30",
                "-t", String.valueOf(segundos),
                "-pix_fmt", "yuv420p",
                destino.toString());
        try {
            pb.start().waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertThat(Files.exists(destino)).isTrue();
        return destino;
    }
}
