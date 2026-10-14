package com.metricol.api.service.reels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.metricol.api.config.ReelsProperties;
import com.metricol.api.config.TenantIdentifierResolver;
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

@SpringBootTest(properties = {
        "app.reels.enabled=false",
        "app.reels.poll-delay-ms=3600000",
        "app.reels.rescue-delay-ms=3600000"
})
class ReelRenderPersistenceTest {

    private final UUID workspace = UUID.randomUUID();
    private final UUID otherWorkspace = UUID.randomUUID();
    private final List<UUID> createdAssets = new ArrayList<>();
    private final List<UUID> createdJobs = new ArrayList<>();

    @Autowired ReelRenderJobRepository jobs;
    @Autowired MediaAssetRepository assets;
    @Autowired ReelRenderService service;
    @Autowired ReelRenderQueue queue;
    @Autowired ReelsProperties properties;

    @BeforeEach
    void enableForRequestTests() {
        properties.setEnabled(true);
    }

    @AfterEach
    void cleanUp() {
        properties.setEnabled(false);
        createdJobs.forEach(jobs::deleteById);
        asTenant(workspace, () -> createdAssets.forEach(assets::deleteById));
        asTenant(otherWorkspace, () -> createdAssets.forEach(assets::deleteById));
    }

    @Test
    @DisplayName("la misma receta devuelve el mismo trabajo y conserva el orden")
    void idempotentAndOrdered() {
        List<UUID> sourceIds = createPhotos(workspace, 4);
        ReelCreateRequest request = request(sourceIds, 20, "Café de otoño");

        ReelRenderResponse first = callAsTenant(workspace, () -> service.create(request, workspace));
        ReelRenderResponse second = callAsTenant(workspace, () -> service.create(request, workspace));
        createdJobs.add(first.id());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.sourceAssetIds()).containsExactlyElementsOf(sourceIds);
        assertThat(first.status()).isEqualTo(ReelRenderStatus.PENDING);
        assertThat(jobs.findByWorkspaceIdOrderByCreatedAtDesc(workspace)).hasSize(1);
    }

    @Test
    @DisplayName("ni las fuentes ni el trabajo se pueden cruzar entre workspaces")
    void tenantIsolation() {
        List<UUID> foreign = createPhotos(otherWorkspace, 4);
        ReelCreateRequest request = request(foreign, 20, null);

        assertThatThrownBy(() -> callAsTenant(workspace, () -> service.create(request, workspace)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace");

        List<UUID> own = createPhotos(workspace, 4);
        ReelRenderResponse response = callAsTenant(workspace, () -> service.create(request(own, 20, null), workspace));
        createdJobs.add(response.id());
        assertThatThrownBy(() -> callAsTenant(otherWorkspace, () -> service.get(response.id(), otherWorkspace)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("un RUNNING abandonado vuelve a PENDING y puede reclamarse otra vez")
    void recoversAfterInterruptedWorker() {
        ReelRenderJob job = jobs.saveAndFlush(job(workspace, 3));
        createdJobs.add(job.getId());
        ReelRenderQueue.Work claimed = queue.claim(job.getId(), "worker-one");
        assertThat(claimed).isNotNull();

        job = jobs.findById(job.getId()).orElseThrow();
        job.setLockedAt(LocalDateTime.now().minusHours(1));
        jobs.saveAndFlush(job);
        properties.setStaleAfterSeconds(10);

        assertThat(queue.rescueAbandoned()).isEqualTo(1);
        ReelRenderJob recovered = jobs.findById(job.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(ReelRenderStatus.PENDING);
        assertThat(recovered.getLockedBy()).isNull();
    }

    @Test
    @DisplayName("una interrupción no supera el máximo de intentos")
    void interruptedWorkerRespectsRetryLimit() {
        ReelRenderJob job = jobs.saveAndFlush(job(workspace, 1));
        createdJobs.add(job.getId());
        assertThat(queue.claim(job.getId(), "worker-one")).isNotNull();

        job = jobs.findById(job.getId()).orElseThrow();
        job.setLockedAt(LocalDateTime.now().minusHours(1));
        jobs.saveAndFlush(job);
        properties.setStaleAfterSeconds(10);

        assertThat(queue.rescueAbandoned()).isZero();
        ReelRenderJob failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReelRenderStatus.FAILED);
        assertThat(failed.getLastError()).contains("agotó sus intentos");
    }

    @Test
    @DisplayName("los reintentos son limitados y terminan en FAILED")
    void retriesAreLimited() {
        ReelRenderJob job = jobs.saveAndFlush(job(workspace, 1));
        createdJobs.add(job.getId());
        assertThat(queue.claim(job.getId(), "worker")).isNotNull();

        queue.failOrRetry(job.getId(), "ffmpeg falló", true);

        ReelRenderJob failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReelRenderStatus.FAILED);
        assertThat(failed.getLastError()).contains("ffmpeg");
        assertThat(failed.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("cantidad, duración y titular se validan antes de encolar")
    void validatesRequest() {
        List<UUID> three = createPhotos(workspace, 3);
        assertThatThrownBy(() -> callAsTenant(workspace,
                () -> service.create(request(three, 20, null), workspace)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entre 4 y 8");

        List<UUID> four = createPhotos(workspace, 4);
        assertThatThrownBy(() -> callAsTenant(workspace,
                () -> service.create(request(four, 14, null), workspace)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entre 15 y 30");
        assertThatThrownBy(() -> callAsTenant(workspace,
                () -> service.create(request(four, 20,
                        "uno dos tres cuatro cinco seis siete ocho nueve diez once doce trece catorce quince "
                                + "dieciseis diecisiete dieciocho diecinueve veinte"), workspace)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zona segura");
    }

    @Test
    @DisplayName("un pendiente se cancela de forma persistente")
    void cancellationIsPersistent() {
        ReelRenderJob job = jobs.saveAndFlush(job(workspace, 3));
        createdJobs.add(job.getId());

        assertThat(queue.cancel(job.getId(), workspace)).isEqualTo(1);
        ReelRenderJob cancelled = jobs.findById(job.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ReelRenderStatus.CANCELLED);
        assertThat(cancelled.getFinishedAt()).isNotNull();
        assertThat(queue.claim(job.getId(), "late-worker")).isNull();
    }

    @Test
    @DisplayName("READY sigue sin aprobación hasta la acción explícita de una persona")
    void approvalIsExplicit() {
        AtomicReference<MediaAsset> result = new AtomicReference<>();
        asTenant(workspace, () -> result.set(assets.saveAndFlush(MediaAsset.builder()
                .fileName("reel-listo.mp4").storageKey("media/" + workspace + "/reel-listo.mp4")
                .url("https://example.test/reel-listo.mp4").thumbnailUrl("https://example.test/portada.jpg")
                .type(MediaType.VIDEO).contentType("video/mp4").sizeBytes(1000L)
                .status(MediaAssetStatus.READY).generadaPorIa(true).build())));
        createdAssets.add(result.get().getId());
        ReelRenderJob job = job(workspace, 3);
        job.setStatus(ReelRenderStatus.READY);
        job.setResultAssetId(result.get().getId());
        job.setFinishedAt(LocalDateTime.now());
        job = jobs.saveAndFlush(job);
        createdJobs.add(job.getId());
        UUID jobId = job.getId();
        UUID user = UUID.randomUUID();

        ReelRenderResponse approved = callAsTenant(workspace,
                () -> service.approve(jobId, workspace, user));

        assertThat(approved.approved()).isTrue();
        ReelRenderJob stored = jobs.findById(jobId).orElseThrow();
        assertThat(stored.getApprovedAt()).isNotNull();
        assertThat(stored.getApprovedBy()).isEqualTo(user);
    }

    private ReelRenderJob job(UUID ws, int maxAttempts) {
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return ReelRenderJob.builder()
                .workspaceId(ws).sourceAssetIds(List.of()).recipeHash(hash.substring(0, 64))
                .recipeVersion("test-v1").recipeJson("{}")
                .outputStorageKey("media/" + ws + "/reels/" + hash.substring(0, 64) + ".mp4")
                .durationSeconds(20).status(ReelRenderStatus.PENDING)
                .runAt(LocalDateTime.now().minusSeconds(1)).maxAttempts(maxAttempts).build();
    }

    private List<UUID> createPhotos(UUID ws, int count) {
        List<UUID> ids = new ArrayList<>();
        asTenant(ws, () -> {
            for (int i = 0; i < count; i++) {
                MediaAsset asset = assets.saveAndFlush(MediaAsset.builder()
                        .fileName("foto-á-" + i + ".jpg")
                        .storageKey("media/" + ws + "/foto-" + i + ".jpg")
                        .url("https://example.test/" + ws + "/foto-" + i + ".jpg")
                        .type(MediaType.IMAGE).contentType("image/jpeg").sizeBytes(100L)
                        .status(MediaAssetStatus.READY).build());
                ids.add(asset.getId());
                createdAssets.add(asset.getId());
            }
        });
        return ids;
    }

    private static ReelCreateRequest request(List<UUID> ids, int duration, String title) {
        ReelCreateRequest request = new ReelCreateRequest();
        request.setAssetIds(ids);
        request.setDurationSeconds(duration);
        request.setTitle(title);
        return request;
    }

    private static void asTenant(UUID workspace, Runnable action) {
        TenantIdentifierResolver.comoTenant(workspace.toString(), action);
    }

    private static <T> T callAsTenant(UUID workspace, java.util.function.Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        TenantIdentifierResolver.comoTenant(workspace.toString(), () -> result.set(action.get()));
        return result.get();
    }
}
