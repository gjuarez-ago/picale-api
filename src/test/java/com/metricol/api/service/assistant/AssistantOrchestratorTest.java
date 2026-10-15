package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricol.api.entity.AssistantMessage;
import com.metricol.api.entity.AssistantRun;
import com.metricol.api.entity.AssistantThread;
import com.metricol.api.entity.AssistantToolCall;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.AssistantRunStatus;
import com.metricol.api.enums.AssistantToolCallStatus;
import com.metricol.api.enums.Permission;
import com.metricol.api.repository.AssistantMessageRepository;
import com.metricol.api.repository.AssistantRunRepository;
import com.metricol.api.repository.AssistantThreadRepository;
import com.metricol.api.repository.AssistantToolCallRepository;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.service.PermissionService;

class AssistantOrchestratorTest {

    private final AssistantThreads threadService = mock(AssistantThreads.class);
    private final AssistantThreadRepository threads = mock(AssistantThreadRepository.class);
    private final AssistantMessageRepository messages = mock(AssistantMessageRepository.class);
    private final AssistantRunRepository runs = mock(AssistantRunRepository.class);
    private final AssistantToolCallRepository calls = mock(AssistantToolCallRepository.class);
    private final MediaAssetRepository media = mock(MediaAssetRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final AssistantMcpClient mcp = mock(AssistantMcpClient.class);
    private final AssistantLlmClient llm = mock(AssistantLlmClient.class);
    private final AssistantRunEvents events = mock(AssistantRunEvents.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AssistantMcpClient.Session session = mock(AssistantMcpClient.Session.class);
    private final ObjectMapper json = new ObjectMapper();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final List<AssistantMessage> history = new ArrayList<>();
    private final List<AssistantToolCall> savedCalls = new ArrayList<>();
    private AssistantOrchestrator service;
    private AssistantThread thread;
    private User user;
    private AssistantRun savedRun;

    @BeforeEach
    void setUp() {
        service = new AssistantOrchestrator(threadService, threads, messages, runs, calls, media,
                permissions, mcp, llm, events, users, publisher, json);
        thread = AssistantThread.builder().id(threadId).workspaceId(workspaceId).createdBy(userId)
                .title("Nueva conversacion").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        user = User.builder().id(userId).workspace(Workspace.builder().id(workspaceId).build()).build();
        var detail = new AssistantThreads.Detail(thread, history, List.of(), List.of());
        when(threadService.detail(user, threadId)).thenReturn(detail);
        when(runs.findByWorkspaceIdAndIdempotencyKey(eq(workspaceId), any())).thenReturn(Optional.empty());
        when(runs.save(any())).thenAnswer(invocation -> {
            savedRun = withId((AssistantRun) invocation.getArgument(0));
            return savedRun;
        });
        when(runs.findLockedById(any())).thenAnswer(invocation -> Optional.ofNullable(savedRun));
        when(calls.save(any())).thenAnswer(invocation -> {
            AssistantToolCall call = withId((AssistantToolCall) invocation.getArgument(0));
            if (!savedCalls.contains(call)) savedCalls.add(call);
            return call;
        });
        when(calls.findByRunIdOrderByCreatedAtAsc(any())).thenAnswer(invocation -> List.copyOf(savedCalls));
        when(messages.save(any())).thenAnswer(invocation -> {
            AssistantMessage message = invocation.getArgument(0);
            if (message.getId() == null) message.setId(UUID.randomUUID());
            history.add(message);
            return message;
        });
        when(messages.findByThreadIdOrderByCreatedAtAsc(threadId)).thenAnswer(invocation -> List.copyOf(history));
        when(permissions.permisosDe(user)).thenReturn(EnumSet.allOf(Permission.class));
        when(mcp.open(eq(user), eq(threadId), any(), any())).thenReturn(session);
        when(users.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void ejecutaLecturaYEntregaRespuestaConRecibo() throws Exception {
        when(session.tools()).thenReturn(json.readTree("""
                [{"name":"panel","description":"Consulta el panel","inputSchema":{"type":"object"},
                  "_meta":{"picale/policy":{"riskLevel":0,"permission":"MEMBERSHIP",
                  "confirmation":"none","idempotencyRequired":false}}}]
                """));
        ObjectNode toolMessage = json.createObjectNode().put("role", "assistant");
        when(llm.complete(any(), any()))
                .thenReturn(new AssistantLlmClient.Turn("", List.of(
                        new AssistantLlmClient.ToolCall("call-1", "panel", json.createObjectNode())),
                        toolMessage, "gpt-test", 10, 2))
                .thenReturn(new AssistantLlmClient.Turn("Tus publicaciones mejoraron.", List.of(),
                        json.createObjectNode().put("role", "assistant").put("content", "ok"),
                        "gpt-test", 12, 4));
        when(session.call("panel", json.createObjectNode()))
                .thenReturn(json.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"resultado real\"}]}"));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Como vamos?", List.of(), "/panel", "message-1"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        assertThat(result.confirmationToken()).isNull();
        verify(session).call(eq("panel"), any());
        verify(calls, atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(call ->
                call.getStatus() == AssistantToolCallStatus.COMPLETED && call.getDurationMs() != null));
        verify(runs, atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(run ->
                run.getStatus() == AssistantRunStatus.COMPLETED));
        assertThat(history).anyMatch(message -> "Tus publicaciones mejoraron.".equals(message.getContent()));
    }

    @Test
    void pausaUnaAccionYGuardaSoloElHashDeConfirmacion() throws Exception {
        when(session.tools()).thenReturn(json.readTree("""
                [{"name":"publicar_ahora","description":"Publica una pieza ahora",
                  "inputSchema":{"type":"object"},"_meta":{"picale/policy":{"riskLevel":3,
                  "permission":"POST_PUBLISH","confirmation":"explicit","idempotencyRequired":true}}}]
                """));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-1", "publicar_ahora",
                        json.createObjectNode().put("postId", "abc"))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 10, 2))
                .thenReturn(new AssistantLlmClient.Turn("La publicacion salio correctamente.", List.of(),
                        json.createObjectNode().put("role", "assistant").put("content", "ok"),
                        "gpt-test", 8, 3));
        when(session.call(eq("publicar_ahora"), any()))
                .thenReturn(json.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"publicada\"}]}"));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Publicalo", List.of(), "/panel", "message-2"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        assertThat(result.confirmationToken()).isNull();
        verify(session, never()).call(any(), any());
        verify(calls, atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(call ->
                call.getStatus() == AssistantToolCallStatus.WAITING_CONFIRMATION
                        && call.getConfirmationTokenHash() != null
                        && call.getConfirmationExpiresAt() != null));
        assertThat(history.get(history.size() - 1).getArtifactsJson()).contains("confirmation");

        AssistantOrchestrator.Result renewed = service.renewConfirmation(user, result.runId());
        assertThat(renewed.confirmationToken()).isNotBlank();
        service.confirm(user, result.runId(), renewed.confirmationToken());

        verify(session).call(eq("publicar_ahora"), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
        assertThatThrownBy(() -> service.confirm(user, result.runId(), renewed.confirmationToken()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ya no espera");
    }

    private static AssistantRun withId(AssistantRun run) {
        if (run.getId() == null) run.setId(UUID.randomUUID());
        return run;
    }

    private static AssistantToolCall withId(AssistantToolCall call) {
        if (call.getId() == null) call.setId(UUID.randomUUID());
        return call;
    }
}
