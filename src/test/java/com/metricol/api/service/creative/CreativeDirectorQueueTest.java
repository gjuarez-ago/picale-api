package com.metricol.api.service.creative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.metricol.api.config.CreativeDirectorProperties;
import com.metricol.api.entity.CreativeBatchJob;
import com.metricol.api.enums.CreativeBatchStatus;
import com.metricol.api.repository.CreativeBatchJobRepository;

@ExtendWith(MockitoExtension.class)
class CreativeDirectorQueueTest {
    @Mock CreativeBatchJobRepository jobs;
    CreativeDirectorProperties properties;
    CreativeDirectorQueue queue;

    @BeforeEach void setUp() {
        properties = new CreativeDirectorProperties();
        queue = new CreativeDirectorQueue(jobs, properties);
    }

    @Test
    void temporaryFailureRetriesButNeverPastLimit() {
        CreativeBatchJob job = running(1, 3);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        queue.failOrRetry(job.getId(), "provider unavailable", true);
        assertThat(job.getStatus()).isEqualTo(CreativeBatchStatus.PENDING);
        assertThat(job.getRunAt()).isAfter(LocalDateTime.now());

        job.setStatus(CreativeBatchStatus.RUNNING); job.setAttempts(3);
        queue.failOrRetry(job.getId(), "provider unavailable", true);
        assertThat(job.getStatus()).isEqualTo(CreativeBatchStatus.FAILED);
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    void invalidPlanFailsWithoutRetry() {
        CreativeBatchJob job = running(1, 3);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        queue.failOrRetry(job.getId(), "invalid plan", false);
        assertThat(job.getStatus()).isEqualTo(CreativeBatchStatus.FAILED);
    }

    @Test
    void restartRecoveryIsBounded() {
        properties.setStaleAfterSeconds(20);
        when(jobs.rescue(any(), any(), any())).thenReturn(2);
        assertThat(queue.rescue()).isEqualTo(2);
        verify(jobs).failExhausted(any(), any(), any());
    }

    private static CreativeBatchJob running(int attempts, int max) {
        return CreativeBatchJob.builder().id(UUID.randomUUID()).status(CreativeBatchStatus.RUNNING)
                .attempts(attempts).maxAttempts(max).runAt(LocalDateTime.now()).build();
    }
}
