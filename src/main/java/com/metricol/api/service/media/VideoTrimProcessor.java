package com.metricol.api.service.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.StorageQuotaService;

/** Materializa una copia solicitada. Nunca modifica el video fuente. */
@Service
public class VideoTrimProcessor {
    private static final Logger log = LoggerFactory.getLogger(VideoTrimProcessor.class);

    private final MediaAssetRepository assets;
    private final R2StorageService storage;
    private final StorageQuotaService quota;
    private final FfmpegImagen ffmpeg;
    private final MiniaturasEnSegundoPlano thumbnails;

    public VideoTrimProcessor(MediaAssetRepository assets, R2StorageService storage,
            StorageQuotaService quota, FfmpegImagen ffmpeg, MiniaturasEnSegundoPlano thumbnails) {
        this.assets = assets;
        this.storage = storage;
        this.quota = quota;
        this.ffmpeg = ffmpeg;
        this.thumbnails = thumbnails;
    }

    public void process(UUID jobId) {
        LocalDateTime now = LocalDateTime.now();
        if (assets.claimVideoTrim(jobId, now, now.minusMinutes(20)) == 0) return;

        MediaAsset job = assets.findById(jobId).orElse(null);
        if (job == null || job.getSourceAssetId() == null) return;
        MediaAsset source = assets.findById(job.getSourceAssetId()).orElse(null);
        if (source == null || source.getStatus() != MediaAssetStatus.READY) {
            fail(job, "El video original ya no esta disponible.");
            return;
        }

        Path output = null;
        try {
            output = Files.createTempFile("picale-trim-", ".mp4");
            if (!ffmpeg.recortarVideo(source.getUrl(), output,
                    job.getTrimStartSeconds(), job.getTrimEndSeconds())) {
                fail(job, "No pude crear el recorte. Intenta otra vez.");
                return;
            }
            long bytes = Files.size(output);
            long reserved = job.getSizeBytes() == null ? 0 : job.getSizeBytes();
            quota.verificarResultado(bytes, reserved, job.getFileName());
            storage.subirArchivo(job.getStorageKey(), output, "video/mp4");

            job.setSizeBytes(bytes);
            job.setStatus(MediaAssetStatus.READY);
            job.setProcessingError(null);
            assets.save(job);
            thumbnails.sacar(job.getId(), job.getTenantId());
        } catch (Exception ex) {
            storage.deleteByKey(job.getStorageKey());
            fail(job, mensaje(ex));
            log.warn("No se pudo recortar el video {}: {}", jobId, ex.toString());
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    log.debug("Quedo un video temporal sin borrar: {}", output);
                }
            }
        }
    }

    private void fail(MediaAsset job, String message) {
        job.setStatus(MediaAssetStatus.FAILED);
        job.setSizeBytes(0L);
        job.setProcessingError(message.length() <= 500 ? message : message.substring(0, 500));
        assets.save(job);
    }

    private static String mensaje(Exception ex) {
        String value = ex.getMessage();
        return value == null || value.isBlank() ? "No pude crear el recorte." : value;
    }
}
