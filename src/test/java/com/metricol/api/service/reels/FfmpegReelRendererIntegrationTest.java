package com.metricol.api.service.reels;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metricol.api.config.ReelsProperties;

class FfmpegReelRendererIntegrationTest {

    @TempDir
    Path inputDirectory;

    @Test
    void rendersVerticalHorizontalAndSquarePhotosWithoutAudio() throws Exception {
        Path artifactDirectory = Path.of("target", "reels-smoke").toAbsolutePath();
        Files.createDirectories(artifactDirectory);
        List<Path> photos = List.of(
                image("vertical-á.jpg", 720, 1280, new Color(15, 84, 140)),
                image("horizontal.jpg", 1280, 720, new Color(206, 82, 48)),
                image("cuadrada.jpg", 900, 900, new Color(30, 140, 86)),
                image("vertical-dos.jpg", 800, 1200, new Color(118, 55, 155)));
        long inputBytes = photos.stream().mapToLong(path -> {
            try {
                return Files.size(path);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }).sum();
        Map<Path, byte[]> originals = photos.stream().collect(Collectors.toMap(path -> path, path -> {
            try {
                return sha256(path);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }));

        ReelsProperties properties = new ReelsProperties();
        properties.setTimeoutSeconds(240);
        long started = System.nanoTime();
        ReelRenderer.Rendered rendered = new FfmpegReelRenderer(properties)
                .render(photos, 15, "Café recién tostado", artifactDirectory);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(rendered.video()).exists();
        assertThat(rendered.cover()).exists();
        assertThat(rendered.metadata().width()).isEqualTo(1080);
        assertThat(rendered.metadata().height()).isEqualTo(1920);
        assertThat(rendered.metadata().videoCodec()).isEqualTo("h264");
        assertThat(rendered.metadata().pixelFormat()).isEqualTo("yuv420p");
        assertThat(rendered.metadata().fastStart()).isTrue();
        for (Path photo : photos) {
            assertThat(sha256(photo)).as("el original %s no cambia", photo.getFileName())
                    .containsExactly(originals.get(photo));
        }

        Process probe = new ProcessBuilder(properties.getFfprobe(), "-v", "error", "-select_streams", "a",
                "-show_entries", "stream=index", "-of", "csv=p=0", rendered.video().toString())
                .redirectErrorStream(true).start();
        assertThat(probe.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(probe.exitValue()).isZero();
        assertThat(new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isBlank();

        Files.writeString(artifactDirectory.resolve("benchmark.txt"),
                "duration_seconds=15\n"
                        + "elapsed_millis=" + elapsedMillis + "\n"
                        + "input_bytes=" + inputBytes + "\n"
                        + "output_bytes=" + rendered.metadata().sizeBytes() + "\n"
                        + "estimated_peak_temp_bytes="
                        + (inputBytes + rendered.metadata().sizeBytes() + Files.size(rendered.cover())) + "\n"
                        + "dimensions=1080x1920\ncodec=h264\npixel_format=yuv420p\n"
                        + "fps=" + rendered.metadata().framesPerSecond() + "\n"
                        + "field_order=" + rendered.metadata().fieldOrder() + "\n"
                        + "faststart=" + rendered.metadata().fastStart() + "\naudio_streams=0\n",
                StandardCharsets.UTF_8);
    }

    private Path image(String name, int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.WHITE);
            graphics.fillOval(width / 4, height / 4, width / 2, height / 2);
        } finally {
            graphics.dispose();
        }
        Path path = inputDirectory.resolve(name);
        ImageIO.write(image, "jpg", path.toFile());
        return path;
    }

    private static byte[] sha256(Path path) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
