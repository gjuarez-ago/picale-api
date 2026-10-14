package com.metricol.api.service.creative;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metricol.api.config.CreativeDirectorProperties;
import com.metricol.api.config.TenantIdentifierResolver;

@Component
public class CreativeDirectorRunner {
    private static final Logger log = LoggerFactory.getLogger(CreativeDirectorRunner.class);
    private final CreativeDirectorService service;
    private final CreativeDirectorQueue queue;
    private final CreativeDirectorProperties properties;

    public CreativeDirectorRunner(CreativeDirectorService service, CreativeDirectorQueue queue,
            CreativeDirectorProperties properties) {
        this.service = service; this.queue = queue; this.properties = properties;
    }

    public void run(CreativeDirectorQueue.Work work) {
        TenantIdentifierResolver.comoTenant(work.workspaceId().toString(), () -> {
            try {
                if (!service.hasPersistedPlan(work.jobId())) {
                    CreativePlan plan = CompletableFuture.supplyAsync(() -> {
                        java.util.concurrent.atomic.AtomicReference<CreativePlan> result = new java.util.concurrent.atomic.AtomicReference<>();
                        TenantIdentifierResolver.comoTenant(work.workspaceId().toString(),
                                () -> result.set(service.plan(work.jobId())));
                        return result.get();
                    }).get(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
                    service.persistPlan(work.jobId(), plan);
                }
                service.generatePreviews(work.jobId());
            } catch (java.util.concurrent.TimeoutException ex) {
                log.warn("creative_event=plan_timeout batch={} workspace={}", work.jobId(), work.workspaceId());
                queue.failOrRetry(work.jobId(), "La IA tardó demasiado en responder.", true);
            } catch (java.util.concurrent.ExecutionException ex) {
                Throwable cause = ex.getCause();
                boolean invalid = cause instanceof IllegalArgumentException;
                log.warn("creative_event={} batch={} workspace={}", invalid ? "plan_invalid" : "plan_provider_error",
                        work.jobId(), work.workspaceId());
                queue.failOrRetry(work.jobId(), invalid ? "La IA devolvió un plan inválido."
                        : "El servicio de IA no respondió.", !invalid);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("creative_event=plan_interrupted batch={} workspace={}", work.jobId(), work.workspaceId());
                queue.failOrRetry(work.jobId(), "La planificación fue interrumpida.", true);
            } catch (RuntimeException ex) {
                log.warn("creative_event=batch_execution_error batch={} workspace={}", work.jobId(), work.workspaceId());
                queue.failOrRetry(work.jobId(), "No se pudo ejecutar el plan.", true);
            }
        });
    }
}
