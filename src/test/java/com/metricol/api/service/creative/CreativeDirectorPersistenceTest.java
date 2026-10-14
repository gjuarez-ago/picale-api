package com.metricol.api.service.creative;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.metricol.api.config.CreativeDirectorProperties;
import com.metricol.api.entity.CreativeBatchJob;
import com.metricol.api.entity.CreativeProposal;
import com.metricol.api.enums.CreativeBatchStatus;
import com.metricol.api.enums.CreativePieceStatus;
import com.metricol.api.enums.CreativePieceType;
import com.metricol.api.repository.CreativeBatchJobRepository;
import com.metricol.api.repository.CreativeProposalRepository;

@SpringBootTest(properties = {
        "app.creative-director.enabled=false",
        "app.creative-director.poll-delay-ms=3600000",
        "app.creative-director.rescue-delay-ms=3600000"
})
class CreativeDirectorPersistenceTest {
    @Autowired CreativeBatchJobRepository jobs;
    @Autowired CreativeProposalRepository proposals;
    @Autowired CreativeDirectorQueue queue;
    @Autowired CreativeDirectorProperties properties;
    private UUID created;
    private UUID proposalCreated;

    @AfterEach void clean() {
        if (proposalCreated != null) proposals.deleteById(proposalCreated);
        if (created != null) jobs.deleteById(created);
    }

    @Test
    void preservesSourceOrderAndClaimsOnlyOnce() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        CreativeBatchJob saved = jobs.saveAndFlush(job(3, List.of(first, second)));
        created = saved.getId();
        assertThat(queue.claim(created, "worker-a")).isNotNull();
        assertThat(queue.claim(created, "worker-b")).isNull();
        assertThat(jobs.findById(created).orElseThrow().getSourceAssetIds()).containsExactly(first, second);
    }

    @Test
    void abandonedWorkReturnsToQueueAfterRestart() {
        CreativeBatchJob saved = jobs.saveAndFlush(job(3, List.of())); created = saved.getId();
        assertThat(queue.claim(created, "dead-worker")).isNotNull();
        saved = jobs.findById(created).orElseThrow();
        saved.setLockedAt(LocalDateTime.now().minusHours(1)); jobs.saveAndFlush(saved);
        properties.setStaleAfterSeconds(10);
        assertThat(queue.rescue()).isEqualTo(1);
        assertThat(jobs.findById(created).orElseThrow().getStatus()).isEqualTo(CreativeBatchStatus.PENDING);
    }

    @Test
    void exhaustedAbandonedWorkFailsPermanently() {
        CreativeBatchJob saved = jobs.saveAndFlush(job(1, List.of())); created = saved.getId();
        assertThat(queue.claim(created, "dead-worker")).isNotNull();
        saved = jobs.findById(created).orElseThrow();
        saved.setLockedAt(LocalDateTime.now().minusHours(1)); jobs.saveAndFlush(saved);
        properties.setStaleAfterSeconds(10);
        assertThat(queue.rescue()).isZero();
        assertThat(jobs.findById(created).orElseThrow().getStatus()).isEqualTo(CreativeBatchStatus.FAILED);
    }

    @Test
    void persistsPreviewUrlsInNarrativeOrder() {
        CreativeBatchJob saved = jobs.saveAndFlush(job(3, List.of()));
        created = saved.getId();
        CreativeProposal proposal = proposals.saveAndFlush(CreativeProposal.builder()
                .jobId(created).workspaceId(saved.getWorkspaceId()).position(0).type(CreativePieceType.CAROUSEL)
                .sourceAssetIds(List.of(UUID.randomUUID(), UUID.randomUUID())).objective("mostrar")
                .captionsJson("{\"INSTAGRAM\":\"Texto\"}").networksCsv("INSTAGRAM")
                .recipeVersion("creative-plan-v1").generationStatus(CreativePieceStatus.READY)
                .previewUrls(List.of("https://cdn/primera.jpg", "https://cdn/segunda.jpg")).build());
        proposalCreated = proposal.getId();

        assertThat(proposals.findById(proposalCreated).orElseThrow().getPreviewUrls())
                .containsExactly("https://cdn/primera.jpg", "https://cdn/segunda.jpg");
    }

    private static CreativeBatchJob job(int maxAttempts, List<UUID> sources) {
        return CreativeBatchJob.builder().workspaceId(UUID.randomUUID()).sourceAssetIds(sources)
                .brief("brief").requestHash(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .recipeVersion("creative-plan-v1").status(CreativeBatchStatus.PENDING)
                .runAt(LocalDateTime.now().minusSeconds(1)).maxAttempts(maxAttempts).build();
    }
}
