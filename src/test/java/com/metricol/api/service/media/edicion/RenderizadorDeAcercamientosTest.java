package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metricol.api.config.EdicionDinamicaProperties;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.media.edicion.PlanDeEdicionVideo.Tramo;

class RenderizadorDeAcercamientosTest {
    @TempDir Path folder;
    private final FfmpegImagen ffmpeg = mock(FfmpegImagen.class);
    private final CompiladorDeAcercamientos compiler = new CompiladorDeAcercamientos(new ValidadorDePlanDeEdicion());

    @Test
    void noSobrescribeOriginalNiOtroArchivoExistente() throws Exception {
        Path source = Files.writeString(folder.resolve("original.mp4"), "source");
        var c = DatosDeAcercamientos.crear(RenderizadorDeAcercamientos.hash(source), new Tramo(0, 4), List.of(DatosDeEdicion.zoom(.5)));
        var renderer = new RenderizadorDeAcercamientos(compiler, ffmpeg, c.control());
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.renderizar(source, source,
                c.preparado(), c.contexto(), c.informe(), null, null, null, 1));
        Path existing = Files.writeString(folder.resolve("otra.mp4"), "keep");
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.renderizar(source, existing,
                c.preparado(), c.contexto(), c.informe(), null, null, null, 1));
        assertThat(Files.readString(existing)).isEqualTo("keep");
        verifyNoInteractions(ffmpeg);
    }

    @Test
    void controlApagadoImpideRenderizarAunqueSeTengaUnPlanAntiguo() throws Exception {
        Path source = Files.writeString(folder.resolve("original.mp4"), "source");
        var c = DatosDeAcercamientos.crear();
        var renderer = new RenderizadorDeAcercamientos(compiler, ffmpeg,
                new ControlDeEdicionDinamica(new EdicionDinamicaProperties()));
        assertThatIllegalStateException().isThrownBy(() -> renderer.renderizar(source, folder.resolve("out.mp4"),
                c.preparado(), c.contexto(), c.informe(), null, null, null, 1));
        verifyNoInteractions(ffmpeg);
    }

    @Test
    void unFalloDeFfmpegNoDejaUnaCopiaFinalNiBorraLaFuente() throws Exception {
        Path source = Files.writeString(folder.resolve("original.mp4"), "source");
        var c = DatosDeAcercamientos.crear(RenderizadorDeAcercamientos.hash(source), new Tramo(0, 4), List.of(DatosDeEdicion.zoom(.5)));
        when(ffmpeg.medirVideo(anyString())).thenReturn(new FfmpegImagen.MedidasVideo(320, 240, 4));
        when(ffmpeg.tienePixelesCuadrados(anyString())).thenReturn(true);
        when(ffmpeg.tieneSonido(anyString())).thenReturn(true);
        when(ffmpeg.editarVideoConAcercamientos(anyString(), any(), any(), any(), anyDouble(), anyBoolean(), any(), any()))
                .thenAnswer(call -> { Files.writeString(call.getArgument(6, Path.class), "partial"); return false; });
        var renderer = new RenderizadorDeAcercamientos(compiler, ffmpeg, c.control());
        Path target = folder.resolve("out.mp4");
        assertThatIOException().isThrownBy(() -> renderer.renderizar(source, target, c.preparado(), c.contexto(),
                c.informe(), null, null, null, 1));
        assertThat(target).doesNotExist();
        assertThat(Files.readString(source)).isEqualTo("source");
        try (var files = Files.list(folder)) { assertThat(files.toList()).containsExactly(source); }
    }
}
