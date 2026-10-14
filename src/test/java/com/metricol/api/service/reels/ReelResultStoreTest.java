package com.metricol.api.service.reels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.ReelRenderJob;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.ReelRenderStatus;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.ReelRenderJobRepository;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.StorageQuotaService;

class ReelResultStoreTest {

    @TempDir Path temp;
    ReelRenderJobRepository jobs = mock(ReelRenderJobRepository.class);
    MediaAssetRepository assets = mock(MediaAssetRepository.class);
    R2StorageService storage = mock(R2StorageService.class);
    StorageQuotaService quota = mock(StorageQuotaService.class);
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    ReelResultStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        store = new ReelResultStore(jobs, assets, storage, quota, transactions);
    }

    @Test
    void createsReadyDerivedAssetOnlyAfterBothUploads() throws Exception {
        UUID workspace = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        String key = ReelResultStore.outputKey(workspace, "a".repeat(64));
        ReelRenderJob job = running(jobId, workspace, key);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(storage.subirArchivo(eq(key), any(Path.class), eq("video/mp4"))).thenReturn("https://cdn/reel.mp4");
        when(storage.subirArchivo(any(String.class), any(Path.class), eq("image/jpeg")))
                .thenReturn("https://cdn/cover.jpg");
        when(assets.save(any(MediaAsset.class))).thenAnswer(invocation -> {
            MediaAsset asset = invocation.getArgument(0);
            asset.setId(assetId);
            return asset;
        });
        Path video = temp.resolve("reel.mp4");
        Path cover = temp.resolve("cover.jpg");
        Files.write(video, new byte[123]);
        Files.write(cover, new byte[12]);

        assertThat(store.uploadAndConfirm(jobId, workspace, video, cover)).isEqualTo(assetId);

        assertThat(job.getStatus()).isEqualTo(ReelRenderStatus.READY);
        assertThat(job.getResultAssetId()).isEqualTo(assetId);
        verify(assets).save(org.mockito.ArgumentMatchers.argThat(asset ->
                asset.getStatus() == MediaAssetStatus.READY
                        && Boolean.TRUE.equals(asset.getGeneradaPorIa())
                        && "https://cdn/reel.mp4".equals(asset.getUrl())
                        && "https://cdn/cover.jpg".equals(asset.getThumbnailUrl())));
    }

    @Test
    void cancellationBetweenRenderAndConfirmationDeletesUploads() throws Exception {
        UUID workspace = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String key = ReelResultStore.outputKey(workspace, "b".repeat(64));
        ReelRenderJob job = running(jobId, workspace, key);
        job.setStatus(ReelRenderStatus.CANCELLED);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(storage.subirArchivo(eq(key), any(Path.class), eq("video/mp4"))).thenReturn("https://cdn/reel.mp4");
        when(storage.subirArchivo(any(String.class), any(Path.class), eq("image/jpeg")))
                .thenReturn("https://cdn/cover.jpg");
        Path video = temp.resolve("reel.mp4");
        Path cover = temp.resolve("cover.jpg");
        Files.write(video, new byte[123]);
        Files.write(cover, new byte[12]);

        assertThat(store.uploadAndConfirm(jobId, workspace, video, cover)).isNull();

        verify(storage).deleteByKey(key);
        verify(storage).deleteByKey(com.metricol.api.service.media.MiniaturaDeVideo.claveDe(key));
        verify(assets, never()).save(any());
    }

    private static ReelRenderJob running(UUID id, UUID workspace, String key) {
        ReelRenderJob job = ReelRenderJob.builder()
                .id(id).workspaceId(workspace).outputStorageKey(key)
                .status(ReelRenderStatus.RUNNING).build();
        return job;
    }
}
