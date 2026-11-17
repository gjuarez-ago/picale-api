package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metricol.api.config.MediaAdaptProperties;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;

/** Integración real: el grafo debe cambiar el encuadre y conservar las capas y el sonido. */
class RenderAcercamientosFfmpegTest {
    @TempDir Path folder;
    private final FfmpegImagen ffmpeg = new FfmpegImagen(new MediaAdaptProperties());

    private Path source(boolean sound) throws Exception {
        Path path = folder.resolve(sound ? "original.mp4" : "mudo.mp4");
        var command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=15:duration=4"));
        if (sound) command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4"));
        command.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", path.toString()));
        run(command);
        return path;
    }

    private RenderizadorDeAcercamientos renderer(DatosDeAcercamientos.Caso c) {
        return new RenderizadorDeAcercamientos(new CompiladorDeAcercamientos(new ValidadorDePlanDeEdicion()), ffmpeg, c.control());
    }

    @Test
    void hayAcercamientoRealEnElPicoYRegresoAlOriginal() throws Exception {
        Path input = source(true), output = folder.resolve("zoom.mp4");
        String originalHash = RenderizadorDeAcercamientos.hash(input);
        var c = DatosDeAcercamientos.crear(originalHash, new Tramo(0, 4), List.of(DatosDeEdicion.zoom(.5)));
        var result = renderer(c).renderizar(input, output, c.preparado(), c.contexto(), c.informe(), null, null, null, 1);
        assertThat(result.width()).isEqualTo(320);
        assertThat(result.height()).isEqualTo(240);
        assertThat(result.durationSeconds()).isBetween(3.95, 4.08);
        assertThat(ffmpeg.tieneSonido(output.toString())).isTrue();
        assertThat(error(frame(input, .2), frame(output, .2))).isLessThan(8);
        assertThat(error(frame(input, 1.4), frame(output, 1.4))).isGreaterThan(8);
        assertThat(error(frame(input, 3), frame(output, 3))).isLessThan(8);
        assertThat(RenderizadorDeAcercamientos.hash(input)).isEqualTo(originalHash);
    }

    @Test
    void logoYSubtitulosSePintanDespuesDelMovimientoConMusicaEnLaMismaPasada() throws Exception {
        Path input = source(true), output = folder.resolve("capas.mp4");
        Path music = folder.resolve("music.wav");
        run(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=660:duration=4", music.toString()));
        var layer = new BufferedImage(320, 240, BufferedImage.TYPE_INT_ARGB);
        var graphics = layer.createGraphics();
        graphics.setColor(java.awt.Color.GREEN);
        graphics.fillRect(250, 10, 60, 40);
        graphics.dispose();
        Path logo = folder.resolve("logo.png");
        ImageIO.write(layer, "png", logo.toFile());
        Path ass = folder.resolve("subtitulos.ass");
        Files.writeString(ass, """
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 320
                PlayResY: 240
                [V4+ Styles]
                Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding
                Style: Default,Arial,20,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1
                [Events]
                Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
                Dialogue: 0,0:00:00.00,0:00:04.00,Default,,0,0,0,,{\\an7\\pos(120,200)\\p1}m 0 0 l 80 0 l 80 16 l 0 16
                """, StandardCharsets.UTF_8);
        var c = DatosDeAcercamientos.crear(RenderizadorDeAcercamientos.hash(input), new Tramo(0, 4),
                List.of(DatosDeEdicion.zoom(.5)));
        renderer(c).renderizar(input, output, c.preparado(), c.contexto(), c.informe(), ass, music, logo, .25);
        var peak = frame(output, 1.4);
        var original = frame(output, 3);
        for (var image : List.of(peak, original)) {
            int green = image.getRGB(255, 15);
            assertThat((green >> 8) & 255).isGreaterThan(160);
            assertThat((green >> 16) & 255).isLessThan(90);
            int white = image.getRGB(160, 208);
            assertThat(white & 255).isGreaterThan(200);
            assertThat((white >> 8) & 255).isGreaterThan(200);
            assertThat((white >> 16) & 255).isGreaterThan(200);
        }
        assertThat(ffmpeg.tieneSonido(output.toString())).isTrue();
    }

    @Test
    void respetaUnRecorteFraccionarioMenorAUnVeinteavoDeSegundo() throws Exception {
        Path input = source(true), output = folder.resolve("recorte.mp4");
        var c = DatosDeAcercamientos.crear(RenderizadorDeAcercamientos.hash(input), new Tramo(.04, 3.54),
                List.of(DatosDeEdicion.zoom(.5)));
        var result = renderer(c).renderizar(input, output, c.preparado(), c.contexto(), c.informe(), null, null, null, 1);
        assertThat(result.durationSeconds()).isBetween(3.42, 3.58);
        assertThat(ffmpeg.tieneSonido(output.toString())).isTrue();
    }

    @Test
    void noInventaUnaPistaDeAudioParaUnVideoMudo() throws Exception {
        Path input = source(false), output = folder.resolve("mudo-zoom.mp4");
        var c = DatosDeAcercamientos.crear(RenderizadorDeAcercamientos.hash(input), new Tramo(0, 4),
                List.of(DatosDeEdicion.zoom(.5)));
        renderer(c).renderizar(input, output, c.preparado(), c.contexto(), c.informe(), null, null, null, 1);
        assertThat(ffmpeg.tieneSonido(output.toString())).isFalse();
    }

    @Test
    void conservaLosTiemposVariablesSinRedondearlosALaFrecuenciaNominal() throws Exception {
        Path input = folder.resolve("vfr.mp4"), output = folder.resolve("vfr-zoom.mp4");
        run(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=15:duration=4",
                "-vf", "settb=1/90000,setpts=PTS+floor(N/15)*450", "-fps_mode:v", "vfr", "-enc_time_base:v", "1:90000",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", input.toString()));
        var c = DatosDeAcercamientos.crear(RenderizadorDeAcercamientos.hash(input), new Tramo(0, 4),
                List.of(DatosDeEdicion.zoom(.5)));
        renderer(c).renderizar(input, output, c.preparado(), c.contexto(), c.informe(), null, null, null, 1);
        var before = timestamps(input);
        var after = timestamps(output);
        assertThat(before).hasSize(60);
        assertThat(after).hasSameSizeAs(before);
        assertThat(before.get(15)).isBetween(1.0049, 1.0051);
        for (int i = 0; i < before.size(); i++) {
            assertThat(Math.abs(before.get(i) - after.get(i))).isLessThan(.00002);
        }
    }

    private static List<Double> timestamps(Path path) throws Exception {
        var process = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "frame=best_effort_timestamp_time", "-of", "csv=p=0", path.toString()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return output.lines().map(s -> s.split(",")[0].trim()).filter(s -> !s.isEmpty()).map(Double::valueOf).toList();
    }

    private BufferedImage frame(Path path, double time) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(ffmpeg.cuadro(path.toString(), time, 320, 2)));
    }

    private static double error(BufferedImage a, BufferedImage b) {
        double total = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) {
            int p = a.getRGB(x, y), q = b.getRGB(x, y);
            for (int shift : new int[]{0, 8, 16}) total += Math.abs(((p >> shift) & 255) - ((q >> shift) & 255));
        }
        return total / (a.getWidth() * a.getHeight() * 3);
    }

    private static void run(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).redirectErrorStream(true).inheritIO().start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }
}
