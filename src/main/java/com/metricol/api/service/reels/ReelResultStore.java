package com.metricol.api.service.reels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.ReelRenderJob;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.ReelRenderStatus;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.ReelRenderJobRepository;
import com.metricol.api.service.media.MiniaturaDeVideo;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.StorageQuotaService;

/** Confirma el activo derivado solo después de que video y portada existen. */
@Service
public class ReelResultStore {

    private static final Logger log = LoggerFactory.getLogger(ReelResultStore.class);

    private final ReelRenderJobRepository jobs;
    private final MediaAssetRepository assets;
    private final R2StorageService storage;
    private final StorageQuotaService quota;
    private final TransactionTemplate transactions;

    public ReelResultStore(ReelRenderJobRepository jobs, MediaAssetRepository assets,
            R2StorageService storage, StorageQuotaService quota, TransactionTemplate transactions) {
        this.jobs = jobs;
        this.assets = assets;
        this.storage = storage;
        this.quota = quota;
        this.transactions = transactions;
    }

    static String outputKey(UUID workspaceId, String recipeHash) {
        return "media/" + workspaceId + "/reels/" + recipeHash + ".mp4";
    }

    /**
     * La clave ya pertenece a la receta. Si el proceso cae después de subir y
     * antes de confirmar, el reintento sobrescribe el mismo objeto.
     */
    public UUID uploadAndConfirm(UUID jobId, UUID workspaceId, Path video, Path cover) {
        ReelRenderJob job = jobs.findById(jobId)
                .orElseThrow(() -> new ReelRenderException("El trabajo de reel ya no existe."));
        if (!workspaceId.equals(job.getWorkspaceId())) {
            throw new ReelRenderException("El trabajo no pertenece al workspace esperado.");
        }
        String videoKey = job.getOutputStorageKey();
        String coverKey = MiniaturaDeVideo.claveDe(videoKey);
        try {
            long size = Files.size(video);
            quota.verificar(size, "reel-" + jobId + ".mp4");
            String videoUrl = storage.subirArchivo(videoKey, video, "video/mp4");
            String coverUrl = storage.subirArchivo(coverKey, cover, "image/jpeg");
            UUID result = transactions.execute(status ->
                    confirm(jobId, workspaceId, videoKey, videoUrl, coverUrl, size));
            if (result == null) {
                deleteQuietly(videoKey);
                deleteQuietly(coverKey);
            }
            return result;
        } catch (RuntimeException ex) {
            // Una subida parcial no queda esperando a la siguiente limpieza.
            deleteQuietly(videoKey);
            deleteQuietly(coverKey);
            throw ex;
        } catch (Exception ex) {
            deleteQuietly(videoKey);
            deleteQuietly(coverKey);
            throw new ReelRenderException("No se pudo guardar el reel generado.", ex);
        }
    }

    private UUID confirm(UUID jobId, UUID workspaceId, String videoKey,
            String videoUrl, String coverUrl, long size) {
        ReelRenderJob job = jobs.findById(jobId)
                .orElseThrow(() -> new ReelRenderException("El trabajo de reel desapareció."));
        if (job.getStatus() == ReelRenderStatus.CANCELLED) {
            return null;
        }
        if (job.getStatus() != ReelRenderStatus.RUNNING) {
            throw new ReelRenderException("El trabajo ya no está en ejecución.");
        }

        MediaAsset asset = job.getResultAssetId() == null ? null : assets.findById(job.getResultAssetId()).orElse(null);
        if (asset == null) {
            asset = new MediaAsset();
            asset.setFileName("reel-" + jobId + ".mp4");
            asset.setStorageKey(videoKey);
            asset.setType(MediaType.VIDEO);
            asset.setContentType("video/mp4");
            asset.setGeneradaPorIa(true);
            asset.setCreadaConIa(false);
        }
        asset.setUrl(videoUrl);
        asset.setThumbnailUrl(coverUrl);
        asset.setSizeBytes(size);
        asset.setStatus(MediaAssetStatus.READY);
        asset = assets.save(asset);

        job.setResultAssetId(asset.getId());
        job.setStatus(ReelRenderStatus.READY);
        job.setFinishedAt(java.time.LocalDateTime.now());
        job.setLockedAt(null);
        job.setLockedBy(null);
        job.setLastError(null);
        jobs.save(job);
        return asset.getId();
    }

    /** Limpieza idempotente del posible objeto de un trabajo cancelado. */
    public void cleanupCancelled(UUID jobId) {
        ReelRenderJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != ReelRenderStatus.CANCELLED) {
            return;
        }
        deleteQuietly(job.getOutputStorageKey());
        deleteQuietly(MiniaturaDeVideo.claveDe(job.getOutputStorageKey()));
    }

    private void deleteQuietly(String key) {
        try {
            storage.deleteByKey(key);
        } catch (RuntimeException ex) {
            log.warn("No se pudo eliminar el objeto temporal {}: {}", key, ex.toString());
        }
    }
}
