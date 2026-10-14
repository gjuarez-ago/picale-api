package com.metricol.api.service.reels;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.ReelsProperties;
import com.metricol.api.entity.ReelRenderJob;
import com.metricol.api.enums.ReelRenderStatus;
import com.metricol.api.repository.ReelRenderJobRepository;

/** Operaciones transaccionales cortas de la cola durable de renders. */
@Service
public class ReelRenderQueue {

    private static final List<ReelRenderStatus> CANCELLABLE = List.of(
            ReelRenderStatus.PENDING, ReelRenderStatus.RUNNING);

    private final ReelRenderJobRepository repository;
    private final ReelsProperties properties;

    public ReelRenderQueue(ReelRenderJobRepository repository, ReelsProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<UUID> due(int limit) {
        return repository.findDue(LocalDateTime.now(), Limit.of(limit));
    }

    @Transactional
    public Work claim(UUID id, String worker) {
        LocalDateTime now = LocalDateTime.now();
        if (repository.claim(id, worker, now) == 0) {
            return null;
        }
        ReelRenderJob job = repository.findById(id).orElse(null);
        return job == null ? null : new Work(job.getId(), job.getWorkspaceId(), job.getAttempts(), job.getMaxAttempts());
    }

    @Transactional
    public void returnToQueue(UUID id) {
        repository.findById(id).ifPresent(job -> {
            if (job.getStatus() == ReelRenderStatus.RUNNING) {
                job.setStatus(ReelRenderStatus.PENDING);
                job.setAttempts(Math.max(0, job.getAttempts() - 1));
                job.setLockedAt(null);
                job.setLockedBy(null);
                job.setRunAt(LocalDateTime.now());
                repository.save(job);
            }
        });
    }

    @Transactional
    public void failOrRetry(UUID id, String reason, boolean retryable) {
        repository.findById(id).ifPresent(job -> {
            if (job.getStatus() != ReelRenderStatus.RUNNING) {
                return;
            }
            int max = job.getMaxAttempts() > 0 ? job.getMaxAttempts() : properties.getMaxAttempts();
            if (retryable && job.getAttempts() < max) {
                job.setStatus(ReelRenderStatus.PENDING);
                job.setRunAt(properties.nextAttempt(job.getAttempts()));
            } else {
                job.setStatus(ReelRenderStatus.FAILED);
                job.setFinishedAt(LocalDateTime.now());
            }
            job.setLockedAt(null);
            job.setLockedBy(null);
            job.setLastError(cut(reason));
            repository.save(job);
        });
    }

    @Transactional
    public int cancel(UUID id, UUID workspaceId) {
        return repository.cancel(id, workspaceId, CANCELLABLE, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public boolean isCancelled(UUID id) {
        return repository.findById(id).map(j -> j.getStatus() == ReelRenderStatus.CANCELLED).orElse(true);
    }

    @Transactional
    public int rescueAbandoned() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime limit = now.minusSeconds(properties.getStaleAfterSeconds());
        repository.failAbandonedExhausted(limit, now,
                "El proceso se interrumpió y el render agotó sus intentos.");
        return repository.rescueAbandoned(limit, now,
                "El proceso que renderizaba el reel se interrumpió; vuelve a la cola.");
    }

    private static String cut(String value) {
        if (value == null || value.isBlank()) {
            return "El render falló sin detalle.";
        }
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    public record Work(UUID jobId, UUID workspaceId, int attempts, int maxAttempts) {
    }
}
