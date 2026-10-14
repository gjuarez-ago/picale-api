package com.metricol.api.service.creative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.metricol.api.enums.MediaType;
import com.metricol.api.enums.PostFormat;
import com.metricol.api.enums.ReelRenderStatus;
import com.metricol.api.models.request.CreativeBatchRequest;
import com.metricol.api.models.request.CreativeProposalTextRequest;
import com.metricol.api.models.response.ReelRenderResponse;
import com.metricol.api.repository.CreativeBatchJobRepository;
import com.metricol.api.repository.CreativeProposalRepository;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.reels.ReelRenderService;
import com.metricol.api.service.ai.AiQuotaGuard;
import com.metricol.api.service.media.AdaptadorDeImagenes;

@ExtendWith(MockitoExtension.class)
class CreativeDirectorServiceTest {
    @Mock CreativeBatchJobRepository jobs;
    @Mock CreativeProposalRepository proposals;
    @Mock MediaAssetRepository assets;
    @Mock CreativePlanner planner;
    @Mock ReelRenderService reels;
    @Mock AiQuotaGuard quota;
    @Mock AdaptadorDeImagenes imageAdapter;
    CreativeDirectorProperties properties;
    CreativeDirectorService service;
    UUID workspace;

    @BeforeEach
    void setUp() {
        properties = new CreativeDirectorProperties(); properties.setEnabled(true);
        service = new CreativeDirectorService(jobs, proposals, assets, planner, properties, reels, quota,
                imageAdapter, new ObjectMapper());
        workspace = UUID.randomUUID();
    }

    @Test
    void createIsIdempotentAndDoesNotModifyOriginal() {
        MediaAsset original = original(workspace);
        when(assets.findAllById(List.of(original.getId()))).thenReturn(List.of(original));
        when(jobs.findByRequestHash(any())).thenReturn(Optional.empty());
        when(jobs.saveAndFlush(any())).thenAnswer(inv -> {
            CreativeBatchJob j = inv.getArgument(0); j.setId(UUID.randomUUID()); return j;
        });
        when(proposals.findByJobIdOrderByPositionAsc(any())).thenReturn(List.of());
        CreativeBatchRequest request = request(original.getId());
        service.create(request, workspace);
        assertThat(original.getUrl()).isEqualTo("https://cdn/original.jpg");
        verify(assets, never()).save(any());
    }

    @Test
    void sameRequestReturnsExistingJobAndFeatureFlagBlocksNewOnes() {
        MediaAsset original = original(workspace);
        CreativeBatchJob existing = CreativeBatchJob.builder().id(UUID.randomUUID()).workspaceId(workspace)
                .sourceAssetIds(List.of(original.getId())).status(CreativeBatchStatus.PENDING).maxAttempts(3).build();
        when(assets.findAllById(List.of(original.getId()))).thenReturn(List.of(original));
        when(jobs.findByRequestHash(any())).thenReturn(Optional.of(existing));
        when(proposals.findByJobIdOrderByPositionAsc(existing.getId())).thenReturn(List.of());
        assertThat(service.create(request(original.getId()), workspace).id()).isEqualTo(existing.getId());
        verify(jobs, never()).saveAndFlush(any());

        properties.setEnabled(false);
        assertThatThrownBy(() -> service.create(request(original.getId()), workspace))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("apagado");
    }

    @Test
    void rejectsAssetFromAnotherWorkspaceBeforeQueueing() {
        MediaAsset foreign = original(UUID.randomUUID());
        when(assets.findAllById(List.of(foreign.getId()))).thenReturn(List.of(foreign));
        assertThatThrownBy(() -> service.create(request(foreign.getId()), workspace))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void composerRequiresApprovalAndReturnsOriginalsWithoutPublishing() throws Exception {
        UUID jobId = UUID.randomUUID(); UUID proposalId = UUID.randomUUID();
        MediaAsset original = original(workspace);
        CreativeProposal proposal = CreativeProposal.builder().id(proposalId).jobId(jobId).workspaceId(workspace)
                .type(CreativePieceType.CAROUSEL).sourceAssetIds(List.of(original.getId()))
                .captionsJson("{\"INSTAGRAM\":\"Texto\"}").networksCsv("INSTAGRAM")
                .generationStatus(CreativePieceStatus.READY).recipeVersion("creative-plan-v1")
                .previewUrls(List.of(original.getUrl())).build();
        when(proposals.findByIdAndWorkspaceId(proposalId, workspace)).thenReturn(Optional.of(proposal));
        assertThatThrownBy(() -> service.composer(proposalId, workspace)).isInstanceOf(IllegalStateException.class);
        proposal.setApprovalStatus(CreativeApprovalStatus.APPROVED);
        proposal.setApprovedAt(LocalDateTime.now());
        var draft = service.composer(proposalId, workspace);
        assertThat(draft.mediaUrls()).containsExactly("https://cdn/original.jpg");
        assertThat(draft.format()).isEqualTo("PHOTO");
        verify(imageAdapter, never()).adaptar(any(), any(), any(), any());
    }

    @Test
    void completeImageFlowUsesTheReviewedPreviewInTheComposerWithoutPublishing() {
        UUID jobId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        CreativeBatchJob job = CreativeBatchJob.builder().id(jobId).workspaceId(workspace)
                .status(CreativeBatchStatus.RUNNING).createdAt(LocalDateTime.now()).build();
        java.util.concurrent.atomic.AtomicReference<CreativeProposal> stored = new java.util.concurrent.atomic.AtomicReference<>();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(proposals.existsByJobId(jobId)).thenReturn(false);
        when(proposals.save(any())).thenAnswer(inv -> {
            CreativeProposal p = inv.getArgument(0);
            if (p.getId() == null) p.setId(proposalId);
            stored.set(p);
            return p;
        });
        when(proposals.findByJobIdOrderByPositionAsc(jobId)).thenAnswer(inv ->
                stored.get() == null ? List.of() : List.of(stored.get()));
        when(assets.findAllById(List.of(assetId))).thenReturn(List.of(original(workspace, assetId)));
        when(imageAdapter.adaptarParaPreview(any(), any(), any(), any()))
                .thenReturn(new AdaptadorDeImagenes.Adaptacion(List.of("https://cdn/revisada.jpg"), false));
        CreativePlan plan = new CreativePlan("creative-plan-v1", List.of(new CreativePlan.Piece(
                CreativePieceType.IMAGE, List.of(assetId), "Mostrar producto", "Producto",
                Map.of("INSTAGRAM", "Texto"), List.of("INSTAGRAM"), null, "Es la toma mas clara")));

        service.persistPlan(jobId, plan);
        service.generatePreviews(jobId);
        when(proposals.findByIdAndWorkspaceId(proposalId, workspace)).thenAnswer(inv -> Optional.of(stored.get()));
        var approved = service.approve(proposalId, workspace, UUID.randomUUID());
        var draft = service.composer(proposalId, workspace);

        assertThat(approved.approved()).isTrue();
        assertThat(approved.previewUrls()).containsExactly("https://cdn/revisada.jpg");
        assertThat(draft.mediaUrls()).containsExactly("https://cdn/revisada.jpg");
        verify(imageAdapter, times(1)).adaptarParaPreview(any(), any(), any(), any());
    }

    @Test
    void storesPartialSuccessWhenOneExecutorCannotStart() {
        UUID jobId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        CreativeBatchJob job = CreativeBatchJob.builder().id(jobId).workspaceId(workspace)
                .status(CreativeBatchStatus.RUNNING).maxAttempts(3).createdAt(LocalDateTime.now()).build();
        List<CreativeProposal> saved = new ArrayList<>();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(proposals.existsByJobId(jobId)).thenReturn(false);
        when(proposals.save(any())).thenAnswer(inv -> {
            CreativeProposal p = inv.getArgument(0);
            if (!saved.contains(p)) saved.add(p);
            return p;
        });
        when(proposals.findByJobIdOrderByPositionAsc(jobId)).thenAnswer(inv -> List.copyOf(saved));
        when(reels.create(any(), any())).thenThrow(new IllegalStateException("off"));
        when(assets.findAllById(List.of(imageId))).thenReturn(List.of(original(workspace, imageId)));
        when(imageAdapter.adaptarParaPreview(any(), any(), any(), any()))
                .thenReturn(new AdaptadorDeImagenes.Adaptacion(List.of("https://cdn/preview.jpg"), false));
        CreativePlan plan = new CreativePlan("creative-plan-v1", List.of(
                new CreativePlan.Piece(CreativePieceType.REEL, fourIds(), "o", null,
                        Map.of("INSTAGRAM", "x"), List.of("INSTAGRAM"), 20, "r"),
                new CreativePlan.Piece(CreativePieceType.IMAGE, List.of(imageId), "o", null,
                        Map.of("INSTAGRAM", "x"), List.of("INSTAGRAM"), null, "r")));
        service.persistPlan(jobId, plan);
        assertThat(job.getStatus()).isEqualTo(CreativeBatchStatus.RUNNING);
        service.generatePreviews(jobId);
        assertThat(job.getStatus()).isEqualTo(CreativeBatchStatus.PARTIAL);
        assertThat(saved.get(1).getPreviewUrls()).containsExactly("https://cdn/preview.jpg");
    }

    @Test
    void approvalIsExplicitAndIdempotent() {
        UUID id = UUID.randomUUID(); UUID user = UUID.randomUUID();
        CreativeProposal proposal = CreativeProposal.builder().id(id).workspaceId(workspace)
                .type(CreativePieceType.IMAGE).sourceAssetIds(List.of(UUID.randomUUID()))
                .captionsJson("{\"INSTAGRAM\":\"Texto\"}").networksCsv("INSTAGRAM")
                .generationStatus(CreativePieceStatus.READY).recipeVersion("creative-plan-v1")
                .previewUrls(List.of("https://cdn/preview.jpg")).build();
        when(proposals.findByIdAndWorkspaceId(id, workspace)).thenReturn(Optional.of(proposal));
        assertThat(service.approve(id, workspace, user).approved()).isTrue();
        LocalDateTime first = proposal.getApprovedAt();
        assertThat(service.approve(id, workspace, user).approvedAt()).isEqualTo(first);
        verify(proposals, org.mockito.Mockito.times(1)).save(proposal);
    }

    @Test
    void editingTextInvalidatesPreviousApproval() {
        UUID id = UUID.randomUUID();
        CreativeProposal proposal = proposal(id, CreativePieceType.IMAGE);
        proposal.setApprovalStatus(CreativeApprovalStatus.APPROVED);
        proposal.setApprovedAt(LocalDateTime.now());
        proposal.setApprovedBy(UUID.randomUUID());
        when(proposals.findByIdAndWorkspaceId(id, workspace)).thenReturn(Optional.of(proposal));
        CreativeProposalTextRequest request = new CreativeProposalTextRequest();
        request.setTitle("  Nuevo titulo  ");
        request.setCaptions(Map.of("INSTAGRAM", "  Nuevo texto  "));

        var response = service.editText(id, workspace, request);

        assertThat(response.title()).isEqualTo("Nuevo titulo");
        assertThat(response.captions()).containsEntry("INSTAGRAM", "Nuevo texto");
        assertThat(response.approvalStatus()).isEqualTo(CreativeApprovalStatus.PENDING);
        assertThat(response.approvedAt()).isNull();
    }

    @Test
    void editingAReelTitleStartsANewPreviewAndRequiresApprovalAgain() {
        UUID id = UUID.randomUUID();
        UUID reelJobId = UUID.randomUUID();
        CreativeProposal proposal = proposal(id, CreativePieceType.REEL);
        proposal.setDurationSeconds(20);
        proposal.setApprovalStatus(CreativeApprovalStatus.APPROVED);
        proposal.setApprovedAt(LocalDateTime.now());
        when(proposals.findByIdAndWorkspaceId(id, workspace)).thenReturn(Optional.of(proposal));
        when(reels.create(any(), org.mockito.ArgumentMatchers.eq(workspace))).thenReturn(new ReelRenderResponse(
                reelJobId, ReelRenderStatus.PENDING, proposal.getSourceAssetIds(), 20, "Titulo nuevo",
                "reel-v1", 0, 3, null, null, null, null, false, null, null, null));
        CreativeProposalTextRequest request = new CreativeProposalTextRequest();
        request.setTitle("Titulo nuevo");
        request.setCaptions(Map.of("INSTAGRAM", "Caption nuevo"));

        var response = service.editText(id, workspace, request);

        assertThat(response.generationStatus()).isEqualTo(CreativePieceStatus.GENERATING);
        assertThat(response.approvalStatus()).isEqualTo(CreativeApprovalStatus.PENDING);
        assertThat(response.reelJobId()).isEqualTo(reelJobId);
        assertThat(response.previewUrls()).isEmpty();
    }

    @Test
    void cannotApproveAReadyFlagWithoutARealPreview() {
        UUID id = UUID.randomUUID();
        CreativeProposal proposal = proposal(id, CreativePieceType.IMAGE);
        proposal.setPreviewUrls(List.of());
        when(proposals.findByIdAndWorkspaceId(id, workspace)).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> service.approve(id, workspace, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("vista previa");
    }

    @Test
    void discardedProposalCannotBeApprovedUntilRegenerated() {
        UUID id = UUID.randomUUID();
        CreativeProposal proposal = proposal(id, CreativePieceType.IMAGE);
        CreativeBatchJob job = CreativeBatchJob.builder().id(proposal.getJobId()).workspaceId(workspace)
                .status(CreativeBatchStatus.READY).maxAttempts(3).build();
        when(proposals.findByIdAndWorkspaceId(id, workspace)).thenReturn(Optional.of(proposal));
        when(jobs.findByIdAndWorkspaceId(proposal.getJobId(), workspace)).thenReturn(Optional.of(job));

        assertThat(service.discard(id, workspace).approvalStatus()).isEqualTo(CreativeApprovalStatus.DISCARDED);
        assertThatThrownBy(() -> service.approve(id, workspace, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("descartada");

        var regenerated = service.regenerate(id, workspace);
        assertThat(regenerated.approvalStatus()).isEqualTo(CreativeApprovalStatus.PENDING);
        assertThat(regenerated.generationStatus()).isEqualTo(CreativePieceStatus.GENERATING);
        assertThat(regenerated.previewUrls()).isEmpty();
        assertThat(job.getStatus()).isEqualTo(CreativeBatchStatus.PENDING);
        service.regenerate(id, workspace);
        verify(jobs, times(1)).save(job);
    }

    @Test
    void generatesAndPersistsPreviewsForImageCarouselAndStoryBeforeApproval() {
        UUID jobId = UUID.randomUUID();
        CreativeBatchJob job = CreativeBatchJob.builder().id(jobId).workspaceId(workspace)
                .status(CreativeBatchStatus.RUNNING).createdAt(LocalDateTime.now()).build();
        CreativeProposal image = generating(jobId, CreativePieceType.IMAGE, List.of(UUID.randomUUID()));
        CreativeProposal carousel = generating(jobId, CreativePieceType.CAROUSEL,
                List.of(UUID.randomUUID(), UUID.randomUUID()));
        CreativeProposal story = generating(jobId, CreativePieceType.STORY, List.of(UUID.randomUUID()));
        List<CreativeProposal> pieces = List.of(image, carousel, story);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(proposals.findByJobIdOrderByPositionAsc(jobId)).thenReturn(pieces);
        when(assets.findAllById(any())).thenAnswer(inv -> ((List<UUID>) inv.getArgument(0)).stream()
                .map(id -> original(workspace, id)).toList());
        when(imageAdapter.adaptarParaPreview(any(), any(), any(), any())).thenAnswer(inv -> {
            List<String> urls = inv.getArgument(1);
            return new AdaptadorDeImagenes.Adaptacion(
                    urls.stream().map(url -> url.replace("original", "preview")).toList(), false);
        });

        service.generatePreviews(jobId);

        assertThat(pieces).allSatisfy(p -> {
            assertThat(p.getGenerationStatus()).isEqualTo(CreativePieceStatus.READY);
            assertThat(p.getPreviewUrls()).isNotEmpty();
        });
        verify(imageAdapter, times(2)).adaptarParaPreview(any(), any(), any(), org.mockito.ArgumentMatchers.eq(PostFormat.PHOTO));
        verify(imageAdapter).adaptarParaPreview(any(), any(), any(), org.mockito.ArgumentMatchers.eq(PostFormat.STORY));
        assertThat(job.getStatus()).isEqualTo(CreativeBatchStatus.READY);
    }

    @Test
    void failedPreviewIsNotExposedAsReadyOrApprovable() {
        UUID jobId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        CreativeBatchJob job = CreativeBatchJob.builder().id(jobId).workspaceId(workspace)
                .status(CreativeBatchStatus.RUNNING).createdAt(LocalDateTime.now()).build();
        CreativeProposal piece = generating(jobId, CreativePieceType.IMAGE, List.of(assetId));
        piece.setId(proposalId);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(proposals.findByJobIdOrderByPositionAsc(jobId)).thenReturn(List.of(piece));
        when(proposals.findByIdAndWorkspaceId(proposalId, workspace)).thenReturn(Optional.of(piece));
        when(assets.findAllById(List.of(assetId))).thenReturn(List.of(original(workspace, assetId)));
        when(imageAdapter.adaptarParaPreview(any(), any(), any(), any()))
                .thenReturn(new AdaptadorDeImagenes.Adaptacion(List.of("https://cdn/original.jpg"), true));

        service.generatePreviews(jobId);

        assertThat(piece.getGenerationStatus()).isEqualTo(CreativePieceStatus.FAILED);
        assertThat(piece.getPreviewUrls()).isEmpty();
        assertThat(job.getStatus()).isEqualTo(CreativeBatchStatus.FAILED);
        assertThatThrownBy(() -> service.approve(proposalId, workspace, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lista");
    }

    @Test
    void mismatchedWorkspaceProposalFailsWithoutReadingItsAssets() {
        UUID jobId = UUID.randomUUID();
        CreativeBatchJob job = CreativeBatchJob.builder().id(jobId).workspaceId(workspace)
                .status(CreativeBatchStatus.RUNNING).createdAt(LocalDateTime.now()).build();
        CreativeProposal foreign = generating(jobId, CreativePieceType.STORY, List.of(UUID.randomUUID()));
        foreign.setWorkspaceId(UUID.randomUUID());
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(proposals.findByJobIdOrderByPositionAsc(jobId)).thenReturn(List.of(foreign));

        service.generatePreviews(jobId);

        assertThat(foreign.getGenerationStatus()).isEqualTo(CreativePieceStatus.FAILED);
        assertThat(foreign.getPreviewUrls()).isEmpty();
        assertThat(job.getStatus()).isEqualTo(CreativeBatchStatus.FAILED);
        verify(assets, never()).findAllById(any());
        verify(imageAdapter, never()).adaptarParaPreview(any(), any(), any(), any());
    }

    @Test
    void failedPlanningCanBeRetriedWithoutCreatingADuplicateBatch() {
        UUID id = UUID.randomUUID();
        CreativeBatchJob job = CreativeBatchJob.builder().id(id).workspaceId(workspace)
                .sourceAssetIds(List.of(UUID.randomUUID())).status(CreativeBatchStatus.FAILED)
                .attempts(3).maxAttempts(3).lastError("provider").build();
        when(jobs.findByIdAndWorkspaceId(id, workspace)).thenReturn(Optional.of(job));
        when(proposals.existsByJobId(id)).thenReturn(false);
        when(jobs.save(job)).thenReturn(job);
        when(proposals.findByJobIdOrderByPositionAsc(id)).thenReturn(List.of());

        var response = service.retry(id, workspace);

        assertThat(response.status()).isEqualTo(CreativeBatchStatus.PENDING);
        assertThat(response.attempts()).isZero();
        assertThat(response.error()).isNull();
        verify(quota).exigirCupo();
    }

    private static CreativeBatchRequest request(UUID id) { CreativeBatchRequest r = new CreativeBatchRequest(); r.setAssetIds(List.of(id)); return r; }
    private static MediaAsset original(UUID ws) { return original(ws, UUID.randomUUID()); }
    private static MediaAsset original(UUID ws, UUID id) { return MediaAsset.builder().id(id).tenantId(ws.toString())
            .type(MediaType.IMAGE).status(MediaAssetStatus.READY).url("https://cdn/original.jpg").build(); }
    private static List<UUID> fourIds() { return List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()); }
    private CreativeProposal proposal(UUID id, CreativePieceType type) {
        return CreativeProposal.builder().id(id).jobId(UUID.randomUUID()).workspaceId(workspace).type(type)
                .sourceAssetIds(List.of(UUID.randomUUID())).captionsJson("{\"INSTAGRAM\":\"Texto\"}")
                .networksCsv("INSTAGRAM").generationStatus(CreativePieceStatus.READY)
                .recipeVersion("creative-plan-v1").previewUrls(List.of("https://cdn/preview.jpg")).build();
    }
    private CreativeProposal generating(UUID jobId, CreativePieceType type, List<UUID> ids) {
        return CreativeProposal.builder().id(UUID.randomUUID()).jobId(jobId).workspaceId(workspace).type(type)
                .sourceAssetIds(ids).captionsJson("{\"INSTAGRAM\":\"Texto\"}").networksCsv("INSTAGRAM")
                .generationStatus(CreativePieceStatus.GENERATING).recipeVersion("creative-plan-v1").build();
    }
}
