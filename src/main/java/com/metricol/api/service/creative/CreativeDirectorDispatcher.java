package com.metricol.api.service.creative;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.metricol.api.config.CreativeDirectorProperties;

@Component
public class CreativeDirectorDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CreativeDirectorDispatcher.class);
    private final CreativeDirectorQueue queue;
    private final CreativeDirectorRunner runner;
    private final ThreadPoolTaskExecutor executor;
    private final CreativeDirectorProperties properties;
    private final String worker = "creative-api-" + ProcessHandle.current().pid();

    public CreativeDirectorDispatcher(CreativeDirectorQueue queue, CreativeDirectorRunner runner,
            @Qualifier("creativeDirectorExecutor") ThreadPoolTaskExecutor executor,
            CreativeDirectorProperties properties) {
        this.queue = queue; this.runner = runner; this.executor = executor; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.creative-director.poll-delay-ms:2000}")
    public void dispatch() {
        if (!properties.isEnabled()) return;
        try {
            int available = executor.getMaxPoolSize() - executor.getActiveCount();
            if (available <= 0) return;
            for (UUID id : queue.due(Math.min(Math.max(0, available), properties.getBatchSize()))) {
                CreativeDirectorQueue.Work work = queue.claim(id, worker);
                if (work == null) continue;
                try { executor.execute(() -> runner.run(work)); }
                catch (RejectedExecutionException ex) { queue.returnToQueue(id); break; }
            }
        } catch (Exception ex) { log.error("Falló el despachador creativo: {}", ex.getMessage()); }
    }

    @Scheduled(fixedDelayString = "${app.creative-director.rescue-delay-ms:60000}")
    public void rescue() {
        if (!properties.isEnabled()) return;
        try { queue.rescue(); } catch (Exception ex) { log.error("No se recuperaron planes creativos: {}", ex.getMessage()); }
    }
}
