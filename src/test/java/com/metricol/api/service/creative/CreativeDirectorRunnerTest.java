package com.metricol.api.service.creative;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.metricol.api.config.CreativeDirectorProperties;

@ExtendWith(MockitoExtension.class)
class CreativeDirectorRunnerTest {
    @Mock CreativeDirectorService service;
    @Mock CreativeDirectorQueue queue;
    CreativeDirectorProperties properties;
    CreativeDirectorRunner runner;
    CreativeDirectorQueue.Work work;

    @BeforeEach
    void setUp() {
        properties = new CreativeDirectorProperties();
        runner = new CreativeDirectorRunner(service, queue, properties);
        work = new CreativeDirectorQueue.Work(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void invalidModelPlanFailsWithoutRetry() {
        when(service.plan(work.jobId())).thenThrow(new IllegalArgumentException("invalid"));

        runner.run(work);

        verify(queue).failOrRetry(eq(work.jobId()), contains("inv"), eq(false));
        verify(service, never()).persistPlan(any(), any());
        verify(service, never()).generatePreviews(any());
    }

    @Test
    void providerFailureIsRetryable() {
        when(service.plan(work.jobId())).thenThrow(new IllegalStateException("provider unavailable"));

        runner.run(work);

        verify(queue).failOrRetry(eq(work.jobId()), contains("IA"), eq(true));
        verify(service, never()).persistPlan(any(), any());
        verify(service, never()).generatePreviews(any());
    }

    @Test
    void timeoutIsRetryableAndDoesNotPersistAPlan() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        lenient().when(service.plan(work.jobId())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        });
        properties.setTimeoutSeconds(0);

        runner.run(work);
        release.countDown();

        verify(queue).failOrRetry(eq(work.jobId()), contains("tard"), eq(true));
        verify(service, never()).persistPlan(any(), any());
        verify(service, never()).generatePreviews(any());
    }

    @Test
    void restartWithPersistedPlanSkipsTheModelAndResumesPreviews() {
        when(service.hasPersistedPlan(work.jobId())).thenReturn(true);

        runner.run(work);

        verify(service, never()).plan(any());
        verify(service, never()).persistPlan(any(), any());
        verify(service).generatePreviews(work.jobId());
    }
}
