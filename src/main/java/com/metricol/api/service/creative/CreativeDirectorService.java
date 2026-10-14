package com.metricol.api.service.creative;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.config.CreativeDirectorProperties;
import com.metricol.api.entity.CreativeBatchJob;
import com.metricol.api.entity.CreativeProposal;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.CreativeBatchStatus;
import com.metricol.api.enums.CreativeApprovalStatus;
import com.metricol.api.enums.CreativePieceStatus;
import com.metricol.api.enums.CreativePieceType;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.enums.ReelRenderStatus;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.CreativeBatchRequest;
import com.metricol.api.models.request.CreativeProposalTextRequest;
import com.metricol.api.models.request.ReelCreateRequest;
import com.metricol.api.models.response.CreativeBatchResponse;
import com.metricol.api.models.response.CreativeComposerDraftResponse;
import com.metricol.api.models.response.CreativeProposalResponse;
import com.metricol.api.models.response.ReelRenderResponse;
import com.metricol.api.repository.CreativeBatchJobRepository;
import com.metricol.api.repository.CreativeProposalRepository;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.reels.ReelRenderService;
import com.metricol.api.service.ai.AiQuotaGuard;
import com.metricol.api.service.media.AdaptadorDeImagenes;

@Service
public class CreativeDirectorService {
    private static final Logger log = LoggerFactory.getLogger(CreativeDirectorService.class);
    private final CreativeBatchJobRepository jobs;
    private final CreativeProposalRepository proposals;
    private final MediaAssetRepository assets;
    private final CreativePlanner planner;
    private final CreativeDirectorProperties properties;
    private final ReelRenderService reels;
    private final AiQuotaGuard quota;
    private final AdaptadorDeImagenes imageAdapter;
    private final ObjectMapper json;

    public CreativeDirectorService(CreativeBatchJobRepository jobs, CreativeProposalRepository proposals,
            MediaAssetRepository assets, CreativePlanner planner, CreativeDirectorProperties properties,
            ReelRenderService reels, AiQuotaGuard quota, AdaptadorDeImagenes imageAdapter, ObjectMapper json) {
        this.jobs = jobs; this.proposals = proposals; this.assets = assets; this.planner = planner;
        this.properties = properties; this.reels = reels; this.quota = quota;
        this.imageAdapter = imageAdapter; this.json = json;
    }

    public CreativeBatchResponse create(CreativeBatchRequest request, UUID workspaceId) {
        if (!properties.isEnabled()) throw new IllegalStateException("El director creativo está apagado temporalmente.");
        quota.exigirCupo();
        List<UUID> ids = request.getAssetIds() == null ? List.of() : List.copyOf(request.getAssetIds());
        if (ids.isEmpty() || ids.size() > 30 || new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("Elige de 1 a 30 archivos sin repetirlos.");
        validateAssets(ids, workspaceId);
        String brief = request.getBrief() == null || request.getBrief().isBlank() ? null : request.getBrief().strip();
        String briefKey = brief == null ? "-1:" : brief.length() + ":" + brief;
        String hash = hash(workspaceId + "|" + properties.getRecipeVersion() + "|" + ids + "|" + briefKey);
        CreativeBatchJob existing = jobs.findByRequestHash(hash).orElse(null);
        if (existing != null) return response(existing);
        CreativeBatchJob job = CreativeBatchJob.builder().workspaceId(workspaceId)
                .sourceAssetIds(new ArrayList<>(ids)).brief(brief).requestHash(hash)
                .recipeVersion(properties.getRecipeVersion()).status(CreativeBatchStatus.PENDING)
                .runAt(LocalDateTime.now()).maxAttempts(properties.getMaxAttempts()).build();
        try {
            CreativeBatchJob saved = jobs.saveAndFlush(job);
            log.info("creative_event=batch_started batch={} workspace={} sources={}", saved.getId(), workspaceId,
                    ids.size());
            return response(saved);
        }
        catch (DataIntegrityViolationException race) {
            return jobs.findByRequestHash(hash).map(this::response).orElseThrow(() -> race);
        }
    }

    @Transactional
    public List<CreativeBatchResponse> list(UUID workspaceId) {
        return jobs.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream().map(this::response).toList();
    }

    @Transactional
    public CreativeBatchResponse get(UUID id, UUID workspaceId) { return response(ownedJob(id, workspaceId)); }

    @Transactional(readOnly = true)
    public CreativePlan plan(UUID jobId) {
        CreativeBatchJob job = jobs.findById(jobId).orElseThrow();
        List<MediaAsset> found = assets.findAllById(job.getSourceAssetIds());
        Map<UUID, MediaAsset> byId = new HashMap<>(); found.forEach(a -> byId.put(a.getId(), a));
        List<CreativePlanner.AssetInput> inputs = job.getSourceAssetIds().stream().map(id -> {
            MediaAsset a = byId.get(id);
            if (a == null || !job.getWorkspaceId().toString().equals(a.getTenantId()))
                throw new IllegalArgumentException("Un archivo ya no está disponible.");
            return new CreativePlanner.AssetInput(a.getId(), a.getType(), a.getFileName(), a.getDescripcionIa(),
                    a.getAgenteAnalisis());
        }).toList();
        return planner.plan(inputs, job.getBrief());
    }

    @Transactional(readOnly = true)
    public boolean hasPersistedPlan(UUID jobId) {
        return proposals.existsByJobId(jobId);
    }

    @Transactional
    public void persistPlan(UUID jobId, CreativePlan plan) {
        CreativeBatchJob job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != CreativeBatchStatus.RUNNING || proposals.existsByJobId(jobId)) return;
        for (int i = 0; i < plan.pieces().size(); i++) {
            CreativePlan.Piece piece = plan.pieces().get(i);
            CreativeProposal proposal = CreativeProposal.builder().jobId(jobId).workspaceId(job.getWorkspaceId())
                    .position(i).type(piece.type()).sourceAssetIds(new ArrayList<>(piece.assetIds()))
                    .objective(piece.objective()).title(piece.title()).captionsJson(write(piece.captions()))
                    .networksCsv(String.join(",", piece.networks())).durationSeconds(piece.durationSeconds())
                    .recipeVersion(plan.recipeVersion()).selectionReason(piece.reason())
                    .generationStatus(CreativePieceStatus.GENERATING).build();
            if (piece.type() == CreativePieceType.REEL) {
                try {
                    ReelCreateRequest request = new ReelCreateRequest();
                    request.setAssetIds(piece.assetIds()); request.setDurationSeconds(piece.durationSeconds());
                    request.setTitle(piece.title());
                    ReelRenderResponse reel = reels.create(request, job.getWorkspaceId());
                    proposal.setReelJobId(reel.id());
                    proposal.setGenerationStatus(map(reel.status()));
                } catch (RuntimeException ex) {
                    proposal.setGenerationStatus(CreativePieceStatus.FAILED);
                    proposal.setGenerationError("No se pudo preparar esta propuesta.");
                    log.warn("creative_event=piece_failed batch={} type=REEL stage=start", jobId);
                }
            }
            proposals.save(proposal);
        }
        job.setPlanJson(write(plan));
        jobs.save(job);
    }

    /** Materializa previews no-reel dentro del worker durable; cada pieza falla por separado. */
    public void generatePreviews(UUID jobId) {
        CreativeBatchJob job = jobs.findById(jobId).orElseThrow();
        List<CreativeProposal> pieces = proposals.findByJobIdOrderByPositionAsc(jobId);
        for (CreativeProposal piece : pieces) {
            if (!job.getWorkspaceId().equals(piece.getWorkspaceId())) {
                piece.setGenerationStatus(CreativePieceStatus.FAILED);
                piece.setGenerationError("No se pudo preparar la vista previa.");
                piece.setPreviewUrls(new ArrayList<>());
                proposals.save(piece);
                log.warn("creative_event=piece_failed batch={} proposal={} stage=workspace", jobId, piece.getId());
                continue;
            }
            if (piece.getType() == CreativePieceType.REEL
                    || piece.getGenerationStatus() != CreativePieceStatus.GENERATING) continue;
            try {
                generateImagePreview(piece);
                piece.setGenerationStatus(CreativePieceStatus.READY);
                piece.setGenerationError(null);
                proposals.save(piece);
                log.info("creative_event=preview_ready batch={} proposal={} type={} media={}", jobId,
                        piece.getId(), piece.getType(), piece.getPreviewUrls().size());
            } catch (RuntimeException ex) {
                piece.setGenerationStatus(CreativePieceStatus.FAILED);
                piece.setGenerationError("No se pudo preparar la vista previa.");
                piece.setPreviewUrls(new ArrayList<>());
                proposals.save(piece);
                log.warn("creative_event=piece_failed batch={} proposal={} type={} stage=preview", jobId,
                        piece.getId(), piece.getType());
            }
        }
        finishBatch(job, pieces);
    }

    @Transactional
    public CreativeProposalResponse approve(UUID proposalId, UUID workspaceId, UUID userId) {
        CreativeProposal p = ownedProposal(proposalId, workspaceId);
        refresh(p);
        if (p.getApprovalStatus() == CreativeApprovalStatus.DISCARDED)
            throw new IllegalStateException("La propuesta descartada debe regenerarse antes de aprobarla.");
        if (p.getGenerationStatus() != CreativePieceStatus.READY)
            throw new IllegalStateException("La pieza debe estar lista antes de aprobarla.");
        if (p.getPreviewUrls() == null || p.getPreviewUrls().isEmpty())
            throw new IllegalStateException("La vista previa debe estar lista antes de aprobarla.");
        if (p.getApprovalStatus() != CreativeApprovalStatus.APPROVED) {
            if (p.getReelJobId() != null) reels.approve(p.getReelJobId(), workspaceId, userId);
            p.setApprovalStatus(CreativeApprovalStatus.APPROVED);
            p.setApprovedAt(LocalDateTime.now()); p.setApprovedBy(userId); proposals.save(p);
            log.info("creative_event=proposal_approved proposal={} workspace={} type={}", p.getId(), workspaceId,
                    p.getType());
        }
        return proposalResponse(p);
    }

    @Transactional
    public CreativeProposalResponse editText(UUID proposalId, UUID workspaceId, CreativeProposalTextRequest request) {
        CreativeProposal p = ownedProposal(proposalId, workspaceId);
        List<String> expected = networks(p);
        Map<String, String> supplied = request.getCaptions();
        if (supplied == null || !supplied.keySet().equals(new HashSet<>(expected)))
            throw new IllegalArgumentException("Incluye exactamente un texto para cada red recomendada.");
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String network : expected) {
            String value = supplied.get(network);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Los textos no pueden estar vacios.");
            normalized.put(network, value.strip());
        }
        String previousTitle = p.getTitle();
        String newTitle = request.getTitle() == null || request.getTitle().isBlank() ? null : request.getTitle().strip();
        p.setTitle(newTitle);
        p.setCaptionsJson(write(normalized));
        requireNewApproval(p);
        if (p.getType() == CreativePieceType.REEL && !Objects.equals(previousTitle, newTitle)) {
            p.setPreviewUrls(new ArrayList<>());
            p.setPreviewThumbnailUrl(null);
            startReel(p, workspaceId);
        }
        proposals.save(p);
        return proposalResponse(p);
    }

    @Transactional
    public CreativeProposalResponse discard(UUID proposalId, UUID workspaceId) {
        CreativeProposal p = ownedProposal(proposalId, workspaceId);
        p.setApprovalStatus(CreativeApprovalStatus.DISCARDED);
        p.setApprovedAt(null); p.setApprovedBy(null);
        proposals.save(p);
        return proposalResponse(p);
    }

    @Transactional
    public CreativeProposalResponse regenerate(UUID proposalId, UUID workspaceId) {
        CreativeProposal p = ownedProposal(proposalId, workspaceId);
        requireNewApproval(p);
        if (p.getGenerationStatus() == CreativePieceStatus.GENERATING) {
            proposals.save(p);
            return proposalResponse(p);
        }
        p.setGenerationError(null);
        p.setPreviewUrls(new ArrayList<>());
        p.setPreviewThumbnailUrl(null);
        if (p.getType() == CreativePieceType.REEL) {
            startReel(p, workspaceId);
        } else {
            p.setGenerationStatus(CreativePieceStatus.GENERATING);
            requeueForPreview(p.getJobId(), workspaceId);
        }
        proposals.save(p);
        log.info("creative_event=proposal_regenerated proposal={} workspace={} type={}", p.getId(), workspaceId,
                p.getType());
        return proposalResponse(p);
    }

    @Transactional
    public CreativeBatchResponse retry(UUID jobId, UUID workspaceId) {
        CreativeBatchJob job = ownedJob(jobId, workspaceId);
        if (job.getStatus() != CreativeBatchStatus.FAILED || proposals.existsByJobId(jobId))
            throw new IllegalStateException("Este analisis no se puede reintentar; regenera la pieza que fallo.");
        quota.exigirCupo();
        job.setStatus(CreativeBatchStatus.PENDING); job.setAttempts(0); job.setLastError(null);
        job.setFinishedAt(null); job.setLockedAt(null); job.setLockedBy(null); job.setRunAt(LocalDateTime.now());
        log.info("creative_event=batch_retried batch={} workspace={}", jobId, workspaceId);
        return response(jobs.save(job));
    }

    @Transactional
    public CreativeComposerDraftResponse composer(UUID proposalId, UUID workspaceId) {
        CreativeProposal p = ownedProposal(proposalId, workspaceId);
        refresh(p);
        if (p.getApprovalStatus() != CreativeApprovalStatus.APPROVED)
            throw new IllegalStateException("Aprueba la pieza antes de llevarla al compositor.");
        if (p.getGenerationStatus() != CreativePieceStatus.READY)
            throw new IllegalStateException("La vista previa todavía no está lista.");
        List<String> urls;
        if (p.getType() == CreativePieceType.REEL) {
            ReelRenderResponse reel = reels.get(p.getReelJobId(), workspaceId);
            if (reel.resultUrl() == null) throw new IllegalStateException("El reel todavía no está disponible.");
            urls = List.of(reel.resultUrl());
        } else {
            urls = List.copyOf(p.getPreviewUrls());
            if (urls.isEmpty()) throw new IllegalStateException("La vista previa todavía no está disponible.");
        }
        Map<String, String> captions = readCaptions(p.getCaptionsJson());
        String caption = captions.values().stream().findFirst().orElse("");
        String format = switch (p.getType()) { case REEL -> "REEL"; case STORY -> "STORY"; default -> "PHOTO"; };
        log.info("creative_event=composer_opened proposal={} workspace={} type={}", p.getId(), workspaceId,
                p.getType());
        return new CreativeComposerDraftResponse(p.getId(), format, p.getTitle(), caption, captions, urls,
                p.getDurationSeconds(), networks(p));
    }

    private void refresh(CreativeProposal p) {
        if (p.getReelJobId() == null || p.getGenerationStatus() == CreativePieceStatus.FAILED) return;
        ReelRenderResponse reel = reels.get(p.getReelJobId(), p.getWorkspaceId());
        CreativePieceStatus current = map(reel.status());
        boolean previewChanged = setReelPreview(p, reel);
        if (current != p.getGenerationStatus() || previewChanged) {
            p.setGenerationStatus(current);
            p.setGenerationError(current == CreativePieceStatus.FAILED ? "No se pudo preparar la vista previa." : null);
            proposals.save(p);
        }
    }

    private CreativeBatchResponse response(CreativeBatchJob job) {
        List<CreativeProposal> entities = proposals.findByJobIdOrderByPositionAsc(job.getId());
        entities.forEach(this::refresh);
        reconcileFinishedBatch(job, entities);
        List<CreativeProposalResponse> pieces = entities.stream().map(this::proposalResponse).toList();
        return new CreativeBatchResponse(job.getId(), List.copyOf(job.getSourceAssetIds()), job.getBrief(),
                job.getStatus(), job.getAttempts(), job.getMaxAttempts(), job.getLastError(), pieces,
                job.getCreatedAt(), job.getUpdatedAt());
    }

    private CreativeProposalResponse proposalResponse(CreativeProposal p) {
        return new CreativeProposalResponse(p.getId(), p.getType(), List.copyOf(p.getSourceAssetIds()),
                p.getObjective(), p.getTitle(), readCaptions(p.getCaptionsJson()), networks(p), p.getDurationSeconds(),
                p.getRecipeVersion(), p.getSelectionReason(), p.getGenerationStatus(), p.getGenerationError(),
                p.getReelJobId(), List.copyOf(p.getPreviewUrls()), p.getPreviewThumbnailUrl(),
                p.getApprovalStatus(), p.getApprovalStatus() == CreativeApprovalStatus.APPROVED, p.getApprovedAt());
    }

    private void generateImagePreview(CreativeProposal p) {
        Map<UUID, MediaAsset> byId = new HashMap<>();
        assets.findAllById(p.getSourceAssetIds()).forEach(a -> byId.put(a.getId(), a));
        List<String> originals = p.getSourceAssetIds().stream().map(id -> {
            MediaAsset a = byId.get(id);
            if (a == null || !p.getWorkspaceId().toString().equals(a.getTenantId())
                    || a.getStatus() != MediaAssetStatus.READY || a.getArchivedAt() != null)
                throw new IllegalStateException("Un original ya no está disponible.");
            return a.getUrl();
        }).toList();
        List<Platform> targetPlatforms = networks(p).stream().map(Platform::valueOf).toList();
        PostFormat format = p.getType() == CreativePieceType.STORY ? PostFormat.STORY : PostFormat.PHOTO;
        AdaptadorDeImagenes.Adaptacion adapted = imageAdapter.adaptarParaPreview(p.getWorkspaceId(), originals,
                targetPlatforms, format);
        if (adapted.huboFallo() || adapted.urls() == null || adapted.urls().size() != originals.size())
            throw new IllegalStateException("No se pudo adaptar la pieza completa.");
        p.setPreviewUrls(new ArrayList<>(adapted.urls()));
    }

    private void finishBatch(CreativeBatchJob job, List<CreativeProposal> pieces) {
        int failed = (int) pieces.stream().filter(p -> p.getGenerationStatus() == CreativePieceStatus.FAILED).count();
        job.setStatus(failed == pieces.size() ? CreativeBatchStatus.FAILED
                : failed > 0 ? CreativeBatchStatus.PARTIAL : CreativeBatchStatus.READY);
        job.setLastError(failed > 0 ? "Algunas piezas no se pudieron preparar." : null);
        job.setFinishedAt(LocalDateTime.now()); job.setLockedAt(null); job.setLockedBy(null);
        jobs.save(job);
        long elapsed = job.getCreatedAt() == null ? -1 : Duration.between(job.getCreatedAt(), job.getFinishedAt()).toMillis();
        log.info("creative_event=batch_finished batch={} workspace={} status={} pieces={} failed={} elapsed_ms={}",
                job.getId(), job.getWorkspaceId(), job.getStatus(), pieces.size(), failed, elapsed);
    }

    private void reconcileFinishedBatch(CreativeBatchJob job, List<CreativeProposal> pieces) {
        if (pieces.isEmpty() || job.getStatus() == CreativeBatchStatus.PENDING
                || job.getStatus() == CreativeBatchStatus.RUNNING) return;
        int failed = (int) pieces.stream().filter(p -> p.getGenerationStatus() == CreativePieceStatus.FAILED).count();
        CreativeBatchStatus desired = failed == pieces.size() ? CreativeBatchStatus.FAILED
                : failed > 0 ? CreativeBatchStatus.PARTIAL : CreativeBatchStatus.READY;
        if (desired != job.getStatus()) {
            job.setStatus(desired);
            job.setLastError(failed > 0 ? "Algunas piezas no se pudieron preparar." : null);
            jobs.save(job);
        }
    }

    private void requeueForPreview(UUID jobId, UUID workspaceId) {
        CreativeBatchJob job = ownedJob(jobId, workspaceId);
        if (job.getStatus() == CreativeBatchStatus.RUNNING || job.getStatus() == CreativeBatchStatus.PENDING) return;
        job.setStatus(CreativeBatchStatus.PENDING); job.setAttempts(0); job.setLastError(null);
        job.setFinishedAt(null); job.setLockedAt(null); job.setLockedBy(null); job.setRunAt(LocalDateTime.now());
        jobs.save(job);
    }

    private void startReel(CreativeProposal p, UUID workspaceId) {
        ReelCreateRequest request = new ReelCreateRequest();
        request.setAssetIds(List.copyOf(p.getSourceAssetIds()));
        request.setDurationSeconds(p.getDurationSeconds());
        request.setTitle(p.getTitle());
        try {
            ReelRenderResponse reel = reels.create(request, workspaceId);
            p.setReelJobId(reel.id());
            p.setGenerationStatus(map(reel.status()));
            p.setGenerationError(null);
            setReelPreview(p, reel);
        } catch (RuntimeException ex) {
            p.setGenerationStatus(CreativePieceStatus.FAILED);
            p.setGenerationError("No se pudo preparar la vista previa.");
            log.warn("creative_event=piece_failed proposal={} type=REEL stage=restart", p.getId());
        }
    }

    private static boolean setReelPreview(CreativeProposal p, ReelRenderResponse reel) {
        List<String> urls = reel.resultUrl() == null ? List.of() : List.of(reel.resultUrl());
        boolean changed = !Objects.equals(p.getPreviewUrls(), urls)
                || !Objects.equals(p.getPreviewThumbnailUrl(), reel.thumbnailUrl());
        if (changed) {
            p.setPreviewUrls(new ArrayList<>(urls));
            p.setPreviewThumbnailUrl(reel.thumbnailUrl());
        }
        return changed;
    }

    private static void requireNewApproval(CreativeProposal p) {
        p.setApprovalStatus(CreativeApprovalStatus.PENDING);
        p.setApprovedAt(null);
        p.setApprovedBy(null);
    }

    private void validateAssets(List<UUID> ids, UUID workspaceId) {
        Map<UUID, MediaAsset> found = new HashMap<>(); assets.findAllById(ids).forEach(a -> found.put(a.getId(), a));
        for (UUID id : ids) {
            MediaAsset a = found.get(id);
            if (a == null || !workspaceId.toString().equals(a.getTenantId()))
                throw new IllegalArgumentException("Uno de los archivos no existe en este workspace.");
            if (a.getStatus() != MediaAssetStatus.READY || a.getArchivedAt() != null)
                throw new IllegalArgumentException("Todos los archivos deben estar listos y visibles.");
        }
    }

    private CreativeBatchJob ownedJob(UUID id, UUID ws) { return jobs.findByIdAndWorkspaceId(id, ws)
            .orElseThrow(() -> new ResourceNotFoundException("Plan creativo no encontrado.")); }
    private CreativeProposal ownedProposal(UUID id, UUID ws) { return proposals.findByIdAndWorkspaceId(id, ws)
            .orElseThrow(() -> new ResourceNotFoundException("Propuesta creativa no encontrada.")); }
    private List<String> networks(CreativeProposal p) { return p.getNetworksCsv() == null || p.getNetworksCsv().isBlank()
            ? List.of() : List.of(p.getNetworksCsv().split(",")); }
    private Map<String, String> readCaptions(String value) {
        try { return json.readValue(value, new TypeReference<LinkedHashMap<String, String>>() {}); }
        catch (Exception ex) { throw new IllegalStateException("El plan guardado no se puede leer."); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("No se pudo guardar el plan."); }
    }
    private static CreativePieceStatus map(ReelRenderStatus status) { return switch (status) {
        case READY -> CreativePieceStatus.READY;
        case FAILED, CANCELLED -> CreativePieceStatus.FAILED;
        default -> CreativePieceStatus.GENERATING;
    }; }
    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
