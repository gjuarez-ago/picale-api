package com.metricol.api.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metricol.api.config.MediaAdaptProperties;

/**
 * La copia final de un video (subtítulos y/o música) contra ffmpeg de verdad:
 * lo que falla aquí es la cadena de filtros, y eso no se ve con un doble.
 * Sin ffmpeg instalado se salta.
 */
class EditarVideoTest {

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
    @DisplayName("Música bajo el sonido original, sin volver a codificar la imagen; dura lo mismo que el video")
    void musicaDeFondo() throws Exception {
        Path video = video(true);
        Path salida = carpeta.resolve("con-musica.mp4");
        assertThat(ffmpeg.editarVideo(video.toString(), null, cancion(), 0.25, 6, ffmpeg.tieneSonido(video.toString()),
                salida)).isTrue();
        assertThat(ffmpeg.tieneSonido(salida.toString())).isTrue();
        assertThat(ffmpeg.medirVideo(salida.toString()).segundos()).isBetween(5.5, 6.5);
    }

    @Test
    @DisplayName("Subtítulos y música en una sola pasada")
    void subtitulosYMusica() throws Exception {
        Path guion = carpeta.resolve("subs.ass");
        Files.writeString(guion, """
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 320
                PlayResY: 240

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, Bold, Alignment
                Style: Default,Arial,24,&H00FFFFFF,0,2

                [Events]
                Format: Layer, Start, End, Style, Text
                Dialogue: 0,0:00:01.00,0:00:03.00,Default,hola
                """, StandardCharsets.UTF_8);
        Path salida = carpeta.resolve("todo.mp4");
        assertThat(ffmpeg.editarVideo(video(true).toString(), guion, cancion(), 0.25, 6, true, salida)).isTrue();
        assertThat(ffmpeg.tieneSonido(salida.toString())).isTrue();
    }

    @Test
    @DisplayName("Un video sin pista de sonido sale con la música sola")
    void sinSonido() throws Exception {
        Path video = video(false);
        assertThat(ffmpeg.tieneSonido(video.toString())).isFalse();
        Path salida = carpeta.resolve("solo-musica.mp4");
        assertThat(ffmpeg.editarVideo(video.toString(), null, cancion(), 1, 6, false, salida)).isTrue();
        assertThat(ffmpeg.tieneSonido(salida.toString())).isTrue();
    }

    @Test
    @DisplayName("Los niveles salen cada décima: un tono suena, el silencio no")
    void niveles() throws Exception {
        double[] tono = ffmpeg.nivelesDeAudio(video(true).toString(), 600);
        assertThat(tono.length).isBetween(58, 61);
        assertThat(Arrays.stream(tono).skip(5).max().orElseThrow()).isGreaterThan(-30);
        assertThat(ffmpeg.nivelesDeAudio(video(false).toString(), 600)).isEmpty();
    }

    @Test
    @DisplayName("El silencio digital (-inf) cuenta como -120")
    void leerNiveles() {
        assertThat(FfmpegImagen.leerNiveles("""
                frame:0 pts:0 pts_time:0
                lavfi.astats.Overall.RMS_level=-inf
                frame:1 pts:1600 pts_time:0.1
                lavfi.astats.Overall.RMS_level=-23.5
                """)).containsExactly(-120, -23.5);
    }

    private Path video(boolean conSonido) throws Exception {
        Path destino = carpeta.resolve(conSonido ? "con-sonido.mp4" : "mudo.mp4");
        if (Files.exists(destino)) return destino;
        List<String> comando = new java.util.ArrayList<>(List.of(props.getFfmpeg(), "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=15:duration=6"));
        if (conSonido) {
            comando.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=300:duration=6", "-c:a", "aac"));
        }
        comando.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-shortest", destino.toString()));
        correr(comando);
        return destino;
    }

    private Path cancion() throws Exception {
        Path destino = carpeta.resolve("cancion.mp3");
        if (!Files.exists(destino)) {
            correr(List.of(props.getFfmpeg(), "-v", "error", "-y", "-f", "lavfi", "-i",
                    "sine=frequency=660:duration=2", destino.toString()));
        }
        return destino;
    }

    private static void correr(List<String> comando) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(comando).inheritIO().start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
    }
}
