package com.metricol.api.service.reels;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.ReelRenderJob;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.ReelRenderJobRepository;
import com.metricol.api.service.reels.ReelRenderQueue.Work;
import com.metricol.api.service.storage.R2StorageService;

/** Ejecuta un trabajo reclamado fuera de cualquier transacción larga. */
@Component
public class ReelRenderRunner {

    private static final Logger log = LoggerFactory.getLogger(ReelRenderRunner.class);

    private final ReelRenderJobRepository jobs;
    private final MediaAssetRepository assets;
    private final R2StorageService storage;
    private final ReelRenderer renderer;
    private final ReelResultStore results;
    private final ReelRenderQueue queue;

    public ReelRenderRunner(ReelRenderJobRepository jobs, MediaAssetRepository assets,
            R2StorageService storage, ReelRenderer renderer, ReelResultStore results, ReelRenderQueue queue) {
        this.jobs = jobs;
        this.assets = assets;
        this.storage = storage;
        this.renderer = renderer;
        this.results = results;
        this.queue = queue;
    }

    public void run(Work work) {
        TenantIdentifierResolver.comoTenant(work.workspaceId().toString(), () -> runAsTenant(work));
    }

    private void runAsTenant(Work work) {
        Path directory = null;
        try {
            if (queue.isCancelled(work.jobId())) {
                return;
            }
            ReelRenderJob job = jobs.findById(work.jobId())
                    .orElseThrow(() -> new PermanentRenderException("El trabajo ya no existe."));
            List<MediaAsset> ordered = orderedSources(job);
            directory = Files.createTempDirectory("picale-reel-" + work.jobId() + "-");
            List<Path> local = download(ordered, directory);
            ReelRenderer.Rendered rendered = renderer.render(local, job.getDurationSeconds(), job.getTitle(), directory);
            if (queue.isCancelled(work.jobId())) {
                return;
            }
            java.util.UUID resultId = results.uploadAndConfirm(
                    work.jobId(), work.workspaceId(), rendered.video(), rendered.cover());
            if (resultId == null) {
                return;
            }
            log.info("Reel {} listo: {} bytes, {}x{}, {} fps", work.jobId(),
                    rendered.metadata().sizeBytes(), rendered.metadata().width(), rendered.metadata().height(),
                    rendered.metadata().framesPerSecond());
        } catch (PermanentRenderException | IllegalArgumentException | QuotaExceededException ex) {
            queue.failOrRetry(work.jobId(), ex.getMessage(), false);
            log.warn("Reel {} no se puede renderizar: {}", work.jobId(), ex.getMessage());
        } catch (Exception ex) {
            queue.failOrRetry(work.jobId(), message(ex), true);
            log.warn("Falló el intento {} del reel {}: {}", work.attempts(), work.jobId(), ex.toString());
        } finally {
            try {
                if (queue.isCancelled(work.jobId())) {
                    results.cleanupCancelled(work.jobId());
                }
            } catch (Exception ex) {
                log.warn("No se pudo completar la limpieza remota del reel {}: {}",
                        work.jobId(), ex.toString());
            } finally {
                deleteTree(directory);
            }
        }
    }

    private List<MediaAsset> orderedSources(ReelRenderJob job) {
        Map<java.util.UUID, MediaAsset> found = new HashMap<>();
        assets.findAllById(job.getSourceAssetIds()).forEach(asset -> found.put(asset.getId(), asset));
        List<MediaAsset> ordered = new ArrayList<>();
        for (java.util.UUID id : job.getSourceAssetIds()) {
            MediaAsset asset = found.get(id);
            if (asset == null || asset.getType() != MediaType.IMAGE || asset.getStatus() != MediaAssetStatus.READY
                    || asset.getStorageKey() == null || asset.getStorageKey().isBlank()) {
                throw new PermanentRenderException("Una fotografía fuente ya no está disponible.");
            }
            ordered.add(asset);
        }
        return ordered;
    }

    private List<Path> download(List<MediaAsset> sources, Path directory) {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            MediaAsset source = sources.get(i);
            Path destination = directory.resolve(String.format("source-%02d%s", i, extension(source.getFileName())));
            try {
                Files.createFile(destination);
            } catch (IOException ex) {
                throw new ReelRenderException("No se pudo preparar el espacio temporal.", ex);
            }
            if (!storage.descargar(source.getStorageKey(), destination)) {
                throw new PermanentRenderException("Una fotografía fuente ya no existe en el almacenamiento.");
            }
            paths.add(destination);
        }
        return paths;
    }

    private static String extension(String name) {
        if (name == null) {
            return ".img";
        }
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot).toLowerCase(java.util.Locale.ROOT) : ".img";
        return ext.matches("\\.(jpg|jpeg|png|webp|heic|avif)") ? ext : ".img";
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // El SO terminará limpiando temp; el fallo queda acotado a este directorio.
                }
            });
        } catch (IOException ignored) {
            // Nunca se borra fuera del directorio creado para este trabajo.
        }
    }

    private static final class PermanentRenderException extends ReelRenderException {
        private PermanentRenderException(String message) {
            super(message);
        }
    }
}
