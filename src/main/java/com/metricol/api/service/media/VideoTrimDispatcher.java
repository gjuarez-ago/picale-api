package com.metricol.api.service.media;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.metricol.api.config.TenantIdentifierResolver;

/** Entrega el trabajo al hilo de video sin perder el tenant. */
@Service
public class VideoTrimDispatcher {
    private final ThreadPoolTaskExecutor executor;
    private final VideoTrimProcessor processor;

    public VideoTrimDispatcher(@Qualifier("videoEditExecutor") ThreadPoolTaskExecutor executor,
            VideoTrimProcessor processor) {
        this.executor = executor;
        this.processor = processor;
    }

    public boolean dispatch(UUID jobId, String tenantId) {
        try {
            executor.execute(() -> TenantIdentifierResolver.comoTenant(
                    tenantId, () -> processor.process(jobId)));
            return true;
        } catch (RejectedExecutionException ex) {
            return false;
        }
    }
}
