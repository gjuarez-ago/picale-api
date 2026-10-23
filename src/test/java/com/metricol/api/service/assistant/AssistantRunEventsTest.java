package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.AssistantRun;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.AssistantRunStatus;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.AssistantRunEventRepository;
import com.metricol.api.repository.AssistantRunRepository;

class AssistantRunEventsTest {

    private final AssistantRunEventRepository eventRepository = mock(AssistantRunEventRepository.class);
    private final AssistantRunRepository runRepository = mock(AssistantRunRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AssistantRunEvents service = new AssistantRunEvents(eventRepository, runRepository, json);

    @Test void activeTimeOnlyIncludesRecordedWorkSegmentsNotHumanWaits() {
        UUID id = UUID.randomUUID();
        when(eventRepository.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(id, 0)).thenReturn(java.util.List.of(
                com.metricol.api.entity.AssistantRunEvent.builder().type("confirmation.required").payloadJson("{\"activeMs\":99999}").build(),
                com.metricol.api.entity.AssistantRunEvent.builder().type("run.segment.completed").payloadJson("{\"activeMs\":100}").build(),
                com.metricol.api.entity.AssistantRunEvent.builder().type("run.segment.completed").payloadJson("{\"activeMs\":200}").build()));
        assertThat(service.activeMillis(id)).isEqualTo(300);
    }

    @Test
    void asignaUnaSecuenciaDurablePorRun() {
        AssistantRun run = run(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(eventRepository.countByRunId(run.getId())).thenReturn(4L);
        when(eventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var event = service.emit(run, "tool.completed", json.createObjectNode().put("ok", true));

        assertThat(event.getSequence()).isEqualTo(5);
        assertThat(event.getType()).isEqualTo("tool.completed");
        assertThat(event.getPayloadJson()).contains("\"ok\":true");
    }

    @Test
    void noPermiteSuscribirseAlRunDeOtraPersona() {
        UUID workspaceId = UUID.randomUUID();
        AssistantRun run = run(UUID.randomUUID(), workspaceId, UUID.randomUUID());
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));
        User other = User.builder().id(UUID.randomUUID())
                .workspace(Workspace.builder().id(workspaceId).build()).build();

        assertThatThrownBy(() -> service.subscribe(other, run.getId(), 0))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static AssistantRun run(UUID id, UUID workspaceId, UUID userId) {
        return AssistantRun.builder().id(id).workspaceId(workspaceId).threadId(UUID.randomUUID()).userId(userId)
                .status(AssistantRunStatus.RUNNING).idempotencyKey("key").createdAt(LocalDateTime.now()).build();
    }
}
