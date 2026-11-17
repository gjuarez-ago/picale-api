package com.metricol.api.service.media.edicion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.AnalisisHablanteProperties;
import com.metricol.api.config.MediaAdaptProperties;

class EjecutorAnalisisHablanteTest {
    @TempDir Path folder;

    private EjecutorAnalisisHablante configured() throws Exception {
        var config = new AnalisisHablanteProperties();
        Path worker = Files.writeString(folder.resolve("worker.py"), "# stub de proceso para pruebas\n");
        Files.writeString(folder.resolve("models.json"), "{}");
        config.setWorker(worker.toString());
        config.setModelsDirectory(folder.toString());
        config.setTimeoutSeconds(1);
        return spy(new EjecutorAnalisisHablante(config, new MediaAdaptProperties()));
    }

    @Test
    void noEstaDisponibleSinPaqueteDeModelos() {
        assertThat(new EjecutorAnalisisHablante(new AnalisisHablanteProperties(), new MediaAdaptProperties())
                .disponible()).isFalse();
    }

    @Test
    void leeReporteValidoPeroRechazaCamposExtraYArchivosVacios() throws Exception {
        var worker = configured();
        Path report = folder.resolve("report.json");
        var mapper = new ObjectMapper();
        Files.writeString(report, mapper.writeValueAsString(DatosDeInformeHablante.valido()));
        assertThat(worker.leerInforme(report).intervals()).hasSize(1);
        Files.writeString(report, mapper.writeValueAsString(DatosDeInformeHablante.valido())
                .replaceFirst("\\{", "{\"command\":\"no autorizado\","));
        assertThatIOException().isThrownBy(() -> worker.leerInforme(report));
        Files.writeString(report, "null");
        assertThatIOException().isThrownBy(() -> worker.leerInforme(report));
        Files.writeString(report, "");
        assertThatIOException().isThrownBy(() -> worker.leerInforme(report));
    }

    @Test
    void timeoutDetieneElWorkerYSusProcesosHijos() throws Exception {
        var worker = configured();
        Process process = mock(Process.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.waitFor(1, TimeUnit.SECONDS)).thenReturn(false);
        when(process.isAlive()).thenReturn(true);
        when(process.descendants()).thenReturn(Stream.of(child));
        when(process.onExit()).thenReturn(CompletableFuture.completedFuture(process));
        when(child.onExit()).thenReturn(CompletableFuture.completedFuture(child));
        doReturn(process).when(worker).iniciar(any(), any());
        assertThatIOException().isThrownBy(() -> worker.analizar(folder.resolve("original.bin"), folder))
                .withMessageContaining("tiempo permitido");
        verify(process).destroyForcibly();
        verify(child).destroyForcibly();
    }

    @Test
    void unaCancelacionConservaLaInterrupcionYDetieneElProceso() throws Exception {
        var worker = configured();
        Process process = mock(Process.class);
        when(process.waitFor(1, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        when(process.isAlive()).thenReturn(true);
        when(process.descendants()).thenReturn(Stream.empty());
        when(process.onExit()).thenReturn(CompletableFuture.completedFuture(process));
        doReturn(process).when(worker).iniciar(any(), any());
        try {
            assertThatIOException().isThrownBy(() -> worker.analizar(folder.resolve("original.bin"), folder));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(process).destroyForcibly();
        } finally {
            Thread.interrupted();
        }
    }
}
