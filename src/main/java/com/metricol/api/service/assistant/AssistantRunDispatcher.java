package com.metricol.api.service.assistant;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.metricol.api.config.TenantIdentifierResolver;

/** Arranca un run solo despues de que su mensaje y su id hayan hecho commit. */
@Component
public class AssistantRunDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AssistantRunDispatcher.class);

    public record Requested(UUID runId, UUID userId, UUID workspaceId) {
    }

    private final ThreadPoolTaskExecutor executor;
    private final ObjectProvider<AssistantOrchestrator> orchestrator;

    public AssistantRunDispatcher(@Qualifier("assistantExecutor") ThreadPoolTaskExecutor executor,
            ObjectProvider<AssistantOrchestrator> orchestrator) {
        this.executor = executor;
        this.orchestrator = orchestrator;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(Requested requested) {
        try {
            executor.execute(() -> TenantIdentifierResolver.comoTenant(requested.workspaceId().toString(),
                    () -> orchestrator.getObject().process(requested)));
        } catch (RejectedExecutionException error) {
            log.warn("No hubo capacidad para el run {} del asistente", requested.runId());
            TenantIdentifierResolver.comoTenant(requested.workspaceId().toString(),
                    () -> orchestrator.getObject().dispatchFailed(requested, "El asistente esta ocupado. Intenta de nuevo."));
        }
    }
}
