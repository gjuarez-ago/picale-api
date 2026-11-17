package com.metricol.api.service.media.edicion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.metricol.api.config.AnalisisHablanteProperties;
import com.metricol.api.config.MediaAdaptProperties;

/** Invoca un worker local fijo. Cada llamada pertenece a una carpeta temporal del coordinador. */
@Component
public class EjecutorAnalisisHablante {
    static final long MAX_REPORT_BYTES = 8L * 1024 * 1024;
    private final AnalisisHablanteProperties properties;
    private final MediaAdaptProperties media;
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(1024).maxNumberLength(40).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public EjecutorAnalisisHablante(AnalisisHablanteProperties properties, MediaAdaptProperties media) {
        this.properties = properties;
        this.media = media;
    }

    public boolean disponible() {
        return properties.getModelsDirectory() != null && !properties.getModelsDirectory().isBlank()
                && Files.isRegularFile(Path.of(properties.getWorker()))
                && Files.isRegularFile(Path.of(properties.getModelsDirectory(), "models.json"));
    }

    public InformeDeHablante analizar(Path original, Path folder) throws IOException {
        if (!disponible()) throw new IllegalStateException("El analizador local todavía no está instalado.");
        Path report = folder.resolve("speaker-analysis.json");
        Process process = iniciar(List.of(properties.getPython(),
                Path.of(properties.getWorker()).toAbsolutePath().toString(),
                "--source", original.toAbsolutePath().toString(), "--output", report.toString(),
                "--models", Path.of(properties.getModelsDirectory()).toAbsolutePath().toString(),
                "--work-dir", folder.toString(), "--ffmpeg", media.getFfmpeg(), "--ffprobe", media.getFfprobe()),
                folder.resolve("analysis.log"));
        try {
            if (!process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS)) {
                throw new IOException("El análisis del video excedió el tiempo permitido.");
            }
            if (process.exitValue() != 0) throw new IOException("No se pudo completar el análisis local del video.");
            return leerInforme(report);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("El análisis del video se interrumpió.", ex);
        } finally {
            if (process.isAlive()) {
                var children = process.descendants().toList();
                children.forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
                try {
                    java.util.concurrent.CompletableFuture.allOf(java.util.stream.Stream.concat(
                            children.stream().map(ProcessHandle::onExit), java.util.stream.Stream.of(process.onExit()))
                            .toArray(java.util.concurrent.CompletableFuture[]::new))
                            .orTimeout(5, TimeUnit.SECONDS).join();
                } catch (java.util.concurrent.CompletionException ignored) {
                    // El cierre también es acotado; se conserva el error original del análisis.
                }
            }
        }
    }

    Process iniciar(List<String> command, Path log) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    InformeDeHablante leerInforme(Path report) throws IOException {
        if (!Files.isRegularFile(report) || Files.size(report) <= 0 || Files.size(report) > MAX_REPORT_BYTES) {
            throw new IOException("El informe del analizador no es válido.");
        }
        var result = json.readValue(Files.readAllBytes(report), InformeDeHablante.class);
        if (result == null) throw new IOException("El informe del analizador está vacío.");
        return result;
    }
}
