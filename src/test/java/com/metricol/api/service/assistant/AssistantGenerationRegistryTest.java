package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.AssistantGenerationRequest;
import com.metricol.api.models.request.CampaignImageRequest;

class AssistantGenerationRegistryTest {
    final AssistantGenerationRequestStore store = mock(AssistantGenerationRequestStore.class);
    final Map<String, AssistantGenerationRequest> persisted = new ConcurrentHashMap<>();
    final UUID workspace = UUID.randomUUID(), run = UUID.randomUUID();
    final AtomicInteger calls = new AtomicInteger();
    final ObjectMapper json = new ObjectMapper();
    CampaignImageRequest request(String brief) {
        return new CampaignImageRequest(1, new CampaignImageRequest.Format("post", null, null), List.of(), null,
                brief, null, List.of(), null, null, false, List.of("INSTAGRAM"));
    }
    AssistantGenerationRegistry registry() { return new AssistantGenerationRegistry(store, json); }
    @BeforeEach void setup() {
        when(store.find(any(), any())).thenAnswer(i -> Optional.ofNullable(persisted.get(i.getArgument(0) + ":" + i.getArgument(1))));
        doAnswer(i -> {
            var entry = AssistantGenerationRequest.builder().workspaceId(i.getArgument(0)).requestId(i.getArgument(1)).fingerprint(i.getArgument(2)).build();
            if (persisted.putIfAbsent(i.getArgument(0) + ":" + i.getArgument(1), entry) != null)
                throw new DataIntegrityViolationException("unique request");
            return null;
        }).when(store).reserve(any(), any(), any());
        doAnswer(i -> {
            persisted.get(i.getArgument(0) + ":" + i.getArgument(1)).setJobId(i.getArgument(2)); return null;
        }).when(store).accepted(any(), any(), any());
    }
    @Test void replayAndNewRegistryInstanceReturnSameJobWithoutGeneratingAgain() {
        assertThat(registry().start(workspace, run, request("Luz"), () -> "job-" + calls.incrementAndGet())).isEqualTo("job-1");
        assertThat(registry().start(workspace, run, request("Luz"), () -> "job-" + calls.incrementAndGet())).isEqualTo("job-1");
        assertThat(calls.get()).isEqualTo(1);
        verify(store, times(1)).reserve(any(), any(), any());
    }
    @Test void uncertainOutcomeKeepsReservationAndPreventsBlindReplay() {
        assertThatThrownBy(() -> registry().start(workspace, run, request("Luz"), () -> {
            calls.incrementAndGet(); throw new IllegalStateException("resultado de red desconocido");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry().start(workspace, run, request("Luz"), () -> "job-" + calls.incrementAndGet()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pendiente de verificar");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void sameRequestCannotChangeParametersAndWorkspaceCannotSeeAnotherJob() {
        registry().start(workspace, run, request("Luz"), () -> "job-a");
        assertThatThrownBy(() -> registry().start(workspace, run, request("Cambiar alimentos"), () -> "otro"))
                .hasMessageContaining("otros parámetros");
        assertThat(registry().start(UUID.randomUUID(), run, request("Luz"), () -> "job-b")).isEqualTo("job-b");
    }
    @Test void failureToPersistAcceptedJobNeverCausesASecondProviderCall() {
        doThrow(new IllegalStateException("database unavailable")).when(store).accepted(any(), any(), any());
        assertThatThrownBy(() -> registry().start(workspace, run, request("Luz"), () -> "job-" + calls.incrementAndGet()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry().start(workspace, run, request("Luz"), () -> "job-" + calls.incrementAndGet()))
                .hasMessageContaining("pendiente de verificar");
        assertThat(calls.get()).isEqualTo(1);
    }
}
