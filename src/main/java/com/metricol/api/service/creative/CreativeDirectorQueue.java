package com.metricol.api.service.creative;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.CreativeDirectorProperties;
import com.metricol.api.entity.CreativeBatchJob;
import com.metricol.api.enums.CreativeBatchStatus;
import com.metricol.api.repository.CreativeBatchJobRepository;

@Service
public class CreativeDirectorQueue {
    private static final Logger log = LoggerFactory.getLogger(CreativeDirectorQueue.class);
    private final CreativeBatchJobRepository jobs;
    private final CreativeDirectorProperties properties;

    public CreativeDirectorQueue(CreativeBatchJobRepository jobs, CreativeDirectorProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<UUID> due(int limit) { return jobs.findDue(LocalDateTime.now(), Limit.of(limit)); }

    @Transactional
    public Work claim(UUID id, String worker) {
        LocalDateTime now = LocalDateTime.now();
        if (jobs.claim(id, worker, now) == 0) return null;
        CreativeBatchJob job = jobs.findById(id).orElse(null);
        return job == null ? null : new Work(job.getId(), job.getWorkspaceId());
    }

    @Transactional
    public void returnToQueue(UUID id) {
        jobs.findById(id).ifPresent(job -> {
            if (job.getStatus() == CreativeBatchStatus.RUNNING) {
                job.setStatus(CreativeBatchStatus.PENDING);
                job.setAttempts(Math.max(0, job.getAttempts() - 1));
                job.setLockedAt(null); job.setLockedBy(null); job.setRunAt(LocalDateTime.now());
                jobs.save(job);
            }
        });
    }

    @Transactional
    public void failOrRetry(UUID id, String reason, boolean retryable) {
        jobs.findById(id).ifPresent(job -> {
            if (job.getStatus() != CreativeBatchStatus.RUNNING) return;
            if (retryable && job.getAttempts() < job.getMaxAttempts()) {
                job.setStatus(CreativeBatchStatus.PENDING);
                job.setRunAt(properties.nextAttempt(job.getAttempts()));
                log.info("creative_event=batch_retry_scheduled batch={} attempt={} max={}", id,
                        job.getAttempts(), job.getMaxAttempts());
            } else {
                job.setStatus(CreativeBatchStatus.FAILED);
                job.setFinishedAt(LocalDateTime.now());
                log.warn("creative_event=batch_failed batch={} attempts={}", id, job.getAttempts());
            }
            job.setLockedAt(null); job.setLockedBy(null); job.setLastError(cut(reason));
            jobs.save(job);
        });
    }

    @Transactional
    public int rescue() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime limit = now.minusSeconds(properties.getStaleAfterSeconds());
        jobs.failExhausted(limit, now, "La planificación se interrumpió y agotó sus intentos.");
        return jobs.rescue(limit, now, "La planificación se interrumpió; vuelve a la cola.");
    }

    private static String cut(String value) {
        String safe = value == null || value.isBlank() ? "No se pudo generar el plan." : value;
        return safe.length() <= 500 ? safe : safe.substring(0, 497) + "...";
    }
    public record Work(UUID jobId, UUID workspaceId) {}
}
