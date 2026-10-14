package com.metricol.api.service.reels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metricol.api.config.ReelsProperties;
import com.metricol.api.service.reels.ReelRenderer.ReelTechnicalMetadata;

class FfmpegReelRendererTest {

    @TempDir
    Path temp;

    @Test
    void commandDeclaresCanonicalContractAndNoAudio() throws Exception {
        FfmpegReelRenderer renderer = new FfmpegReelRenderer(new ReelsProperties());
        List<String> command = renderer.buildCommand(
                List.of(temp.resolve("vertical.jpg"), temp.resolve("horizontal.jpg"),
                        temp.resolve("cuadrada.jpg"), temp.resolve("foto con acento á.jpg")),
                20, "Café recién tostado", temp, temp.resolve("reel.mp4"));

        assertThat(command).contains("libx264", "yuv420p", "+faststart", "+cgop", "-an");
        assertThat(String.join(" ", command)).contains("1080x1920", "drawtext", "concat=n=4");
    }

    @Test
    void wrapsTitleInsideThreeLines() {
        assertThat(FfmpegReelRenderer.wrapTitle("Café recién tostado para compartir hoy"))
                .contains("Café").contains(System.lineSeparator());
        assertThatThrownBy(() -> FfmpegReelRenderer.wrapTitle(
                "uno dos tres cuatro cinco seis siete ocho nueve diez once doce trece catorce quince dieciseis "
                        + "diecisiete dieciocho diecinueve veinte"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zona segura");
    }

    @Test
    void rejectsOutputThatDoesNotMatchContract() {
        assertThatThrownBy(() -> FfmpegReelRenderer.validate(
                new ReelTechnicalMetadata(1920, 1080, "h264", "yuv420p", "progressive", 30, true, 100)))
                .hasMessageContaining("1080x1920");
        assertThatThrownBy(() -> FfmpegReelRenderer.validate(
                new ReelTechnicalMetadata(1080, 1920, "h264", "yuv444p", "progressive", 30, true, 100)))
                .hasMessageContaining("H.264/yuv420p");
        assertThatThrownBy(() -> FfmpegReelRenderer.validate(
                new ReelTechnicalMetadata(1080, 1920, "h264", "yuv420p", "progressive", 30, false, 100)))
                .hasMessageContaining("faststart");
    }

    @Test
    void reportsProcessFailureAndTimeout() {
        FfmpegReelRenderer renderer = new FfmpegReelRenderer(new ReelsProperties());
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath().toString();

        assertThatThrownBy(() -> renderer.runProcess(
                List.of(java, "-cp", classpath, ReelTestProcess.class.getName(), "fail"), 5, "proceso de prueba"))
                .isInstanceOf(ReelRenderException.class)
                .hasMessageContaining("código 7").hasMessageContaining("fallo esperado");
        assertThatThrownBy(() -> renderer.runProcess(
                List.of(java, "-cp", classpath, ReelTestProcess.class.getName(), "sleep"), 1, "proceso de prueba"))
                .isInstanceOf(ReelRenderException.class)
                .hasMessageContaining("límite de 1 segundos");
    }
}
