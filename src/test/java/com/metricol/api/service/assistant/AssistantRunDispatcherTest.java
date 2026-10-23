package com.metricol.api.service.assistant;

import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AssistantRunDispatcherTest {
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final AssistantOrchestrator orchestrator = mock(AssistantOrchestrator.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AssistantOrchestrator> provider = mock(ObjectProvider.class);
    private final AssistantRunDispatcher.Requested request = new AssistantRunDispatcher.Requested(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    private AssistantRunDispatcher dispatcher() {
        when(provider.getObject()).thenReturn(orchestrator);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        return new AssistantRunDispatcher(executor, provider);
    }

    @Test void validatesVisualRuntimeWithoutProviderCalls() {
        dispatcher().verifyRuntime();
        verifyNoInteractions(orchestrator, executor);
    }

    @Test void completesNormallyWithoutRecovery() {
        dispatcher().dispatch(request);
        verify(orchestrator).process(request);
        verify(orchestrator, never()).workerFailed(any());
    }

    @Test void missingClassMarksRunFailedAfterProcessRollbackWithoutRetry() {
        var dispatcher = dispatcher();
        doThrow(new NoClassDefFoundError("AssistantToolResult")).when(orchestrator).process(request);
        dispatcher.dispatch(request);
        var order = inOrder(orchestrator);
        order.verify(orchestrator).process(request);
        order.verify(orchestrator).workerFailed(request);
        verify(orchestrator, times(1)).process(request);
    }

    @Test void transactionCommitFailureAlsoMarksRunFailed() {
        var dispatcher = dispatcher();
        doThrow(new IllegalStateException("commit failed")).when(orchestrator).process(request);
        dispatcher.dispatch(request);
        verify(orchestrator).workerFailed(request);
    }

    @Test void saturatedQueuePersistsFriendlyFailure() {
        var dispatcher = dispatcher();
        doThrow(new RejectedExecutionException()).when(executor).execute(any(Runnable.class));
        dispatcher.dispatch(request);
        verify(orchestrator).dispatchFailed(eq(request), anyString());
        verify(orchestrator, never()).process(any());
    }
}
