package com.metricol.api.service.reels;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.metricol.api.config.ReelsProperties;
import com.metricol.api.service.reels.ReelRenderQueue.Work;

/** Reclama solo tantos trabajos persistentes como workers libres existen. */
@Component
public class ReelRenderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReelRenderDispatcher.class);

    private final ReelRenderQueue queue;
    private final ReelRenderRunner runner;
    private final ThreadPoolTaskExecutor executor;
    private final ReelsProperties properties;
    private final String worker = "reels-api-" + ProcessHandle.current().pid();

    public ReelRenderDispatcher(ReelRenderQueue queue, ReelRenderRunner runner,
            @Qualifier("reelExecutor") ThreadPoolTaskExecutor executor, ReelsProperties properties) {
        this.queue = queue;
        this.runner = runner;
        this.executor = executor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.reels.poll-delay-ms:2000}")
    public void dispatch() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            int available = executor.getMaxPoolSize() - executor.getActiveCount();
            if (available <= 0) {
                return;
            }
            int limit = Math.min(available, properties.getBatchSize());
            for (UUID id : queue.due(limit)) {
                Work work = queue.claim(id, worker);
                if (work == null) {
                    continue;
                }
                try {
                    executor.execute(() -> runner.run(work));
                } catch (RejectedExecutionException ex) {
                    queue.returnToQueue(id);
                    break;
                }
            }
        } catch (Exception ex) {
            log.error("El despachador de reels falló esta vuelta: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.reels.rescue-delay-ms:120000}")
    public void rescue() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            int rescued = queue.rescueAbandoned();
            if (rescued > 0) {
                log.warn("Reels recuperados tras una interrupción: {}", rescued);
            }
        } catch (Exception ex) {
            log.error("No se pudieron recuperar renders abandonados: {}", ex.getMessage());
        }
    }
}
