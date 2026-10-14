package com.metricol.api.service.reels;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.ReelsProperties;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.ReelRenderJob;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.ReelRenderStatus;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.ReelCreateRequest;
import com.metricol.api.models.response.ReelRenderResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.ReelRenderJobRepository;

/** API de creación, consulta, cancelación y aprobación humana. */
@Service
public class ReelRenderService {

    private final ReelRenderJobRepository jobs;
    private final MediaAssetRepository assets;
    private final ReelsProperties properties;
    private final ReelRenderQueue queue;
    private final ReelResultStore results;

    public ReelRenderService(ReelRenderJobRepository jobs, MediaAssetRepository assets,
            ReelsProperties properties, ReelRenderQueue queue, ReelResultStore results) {
        this.jobs = jobs;
        this.assets = assets;
        this.properties = properties;
        this.queue = queue;
        this.results = results;
    }

    public ReelRenderResponse create(ReelCreateRequest request, UUID workspaceId) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("La generación de reels está apagada temporalmente.");
        }
        List<UUID> ids = request.getAssetIds() == null ? List.of() : List.copyOf(request.getAssetIds());
        if (ids.size() < properties.getMinPhotos() || ids.size() > properties.getMaxPhotos()) {
            throw new IllegalArgumentException("Elige entre " + properties.getMinPhotos() + " y "
                    + properties.getMaxPhotos() + " fotografías.");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("Una fotografía no puede aparecer dos veces en el mismo reel.");
        }
        int duration = request.getDurationSeconds() == null
                ? properties.getDefaultDurationSeconds() : request.getDurationSeconds();
        if (duration < properties.getMinDurationSeconds() || duration > properties.getMaxDurationSeconds()) {
            throw new IllegalArgumentException("La duración debe estar entre " + properties.getMinDurationSeconds()
                    + " y " + properties.getMaxDurationSeconds() + " segundos.");
        }
        String title = request.getTitle() == null || request.getTitle().isBlank() ? null : request.getTitle().strip();
        if (title != null) {
            FfmpegReelRenderer.wrapTitle(title);
        }
        validateSources(ids, workspaceId);

        ReelRecipe recipe = new ReelRecipe(properties.getRecipeVersion(), workspaceId, ids, duration, title);
        ReelRenderJob existing = jobs.findByRecipeHash(recipe.hash()).orElse(null);
        if (existing != null) {
            return response(existing);
        }
        ReelRenderJob job = ReelRenderJob.builder()
                .workspaceId(workspaceId)
                .sourceAssetIds(new ArrayList<>(ids))
                .recipeHash(recipe.hash())
                .recipeVersion(recipe.version())
                .recipeJson(recipe.json())
                .outputStorageKey(ReelResultStore.outputKey(workspaceId, recipe.hash()))
                .durationSeconds(duration)
                .title(title)
                .status(ReelRenderStatus.PENDING)
                .runAt(LocalDateTime.now())
                .attempts(0)
                .maxAttempts(properties.getMaxAttempts())
                .build();
        try {
            return response(jobs.saveAndFlush(job));
        } catch (DataIntegrityViolationException race) {
            return jobs.findByRecipeHash(recipe.hash())
                    .map(this::response)
                    .orElseThrow(() -> race);
        }
    }

    @Transactional(readOnly = true)
    public List<ReelRenderResponse> list(UUID workspaceId) {
        return jobs.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public ReelRenderResponse get(UUID id, UUID workspaceId) {
        return response(owned(id, workspaceId));
    }

    public ReelRenderResponse cancel(UUID id, UUID workspaceId) {
        ReelRenderJob job = owned(id, workspaceId);
        if (job.getStatus() == ReelRenderStatus.READY || job.getStatus() == ReelRenderStatus.FAILED) {
            throw new IllegalStateException("Ese render ya terminó y no se puede cancelar.");
        }
        queue.cancel(id, workspaceId);
        results.cleanupCancelled(id);
        return get(id, workspaceId);
    }

    @Transactional
    public ReelRenderResponse approve(UUID id, UUID workspaceId, UUID userId) {
        ReelRenderJob job = owned(id, workspaceId);
        if (job.getStatus() != ReelRenderStatus.READY || job.getResultAssetId() == null) {
            throw new IllegalStateException("El reel debe estar listo antes de aprobarlo.");
        }
        if (job.getApprovedAt() == null) {
            job.setApprovedAt(LocalDateTime.now());
            job.setApprovedBy(userId);
            jobs.save(job);
        }
        return response(job);
    }

    private void validateSources(List<UUID> ids, UUID workspaceId) {
        List<MediaAsset> found = assets.findAllById(ids);
        Map<UUID, MediaAsset> byId = new HashMap<>();
        found.forEach(asset -> byId.put(asset.getId(), asset));
        for (UUID id : ids) {
            MediaAsset asset = byId.get(id);
            if (asset == null || !workspaceId.toString().equals(asset.getTenantId())) {
                throw new IllegalArgumentException("Una de las fotografías no existe en este workspace.");
            }
            if (asset.getType() != MediaType.IMAGE || asset.getStatus() != MediaAssetStatus.READY
                    || asset.getStorageKey() == null || asset.getStorageKey().isBlank()) {
                throw new IllegalArgumentException("Todos los archivos deben ser fotografías listas para usar.");
            }
        }
    }

    private ReelRenderJob owned(UUID id, UUID workspaceId) {
        return jobs.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Render de reel no encontrado."));
    }

    private ReelRenderResponse response(ReelRenderJob job) {
        MediaAsset result = null;
        if (job.getResultAssetId() != null) {
            result = assets.findById(job.getResultAssetId()).orElse(null);
        }
        return new ReelRenderResponse(job.getId(), job.getStatus(), List.copyOf(job.getSourceAssetIds()),
                job.getDurationSeconds(), job.getTitle(), job.getRecipeVersion(), job.getAttempts(),
                job.getMaxAttempts(), job.getLastError(), job.getResultAssetId(),
                result == null ? null : result.getUrl(), result == null ? null : result.getThumbnailUrl(),
                job.getApprovedAt() != null, job.getCreatedAt(), job.getUpdatedAt(), job.getFinishedAt());
    }
}
