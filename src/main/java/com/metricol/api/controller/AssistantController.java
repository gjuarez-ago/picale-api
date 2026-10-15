package com.metricol.api.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.metricol.api.entity.AssistantMessage;
import com.metricol.api.entity.AssistantRun;
import com.metricol.api.entity.AssistantThread;
import com.metricol.api.entity.AssistantToolCall;
import com.metricol.api.entity.User;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.assistant.AssistantThreads;
import com.metricol.api.service.assistant.AssistantOrchestrator;
import com.metricol.api.service.assistant.AssistantRunEvents;

/** API publica de conversaciones; la ejecucion y el SSE se agregan sobre estos IDs durables. */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    public record CreateThreadRequest(String title) {
    }

    public record SendMessageRequest(String content, List<UUID> attachmentIds, String currentPath,
            String idempotencyKey, String promptName) {
    }

    public record ConfirmRequest(String token) {
    }

    public record ThreadSummary(UUID id, String title, LocalDateTime updatedAt, boolean closed) {
    }

    public record MessageView(UUID id, UUID runId, String role, String content, String attachmentsJson,
            String artifactsJson, LocalDateTime createdAt) {
    }

    public record RunView(UUID id, String status, String intent, String errorCode, String errorMessage,
            LocalDateTime createdAt, LocalDateTime finishedAt) {
    }

    public record ToolCallView(UUID id, UUID runId, String toolName, int riskLevel, String permission,
            String status, String resultSummary, String affectedEntityType, String affectedEntityId,
            LocalDateTime createdAt) {
    }

    public record ThreadDetail(UUID id, String title, String summary, UUID campaignId, LocalDateTime createdAt,
            LocalDateTime updatedAt, LocalDateTime closedAt, List<MessageView> messages, List<RunView> runs,
            List<ToolCallView> toolCalls, String pendingConfirmationToken) {
    }

    private final AssistantThreads threads;
    private final AssistantOrchestrator orchestrator;
    private final AssistantRunEvents events;

    public AssistantController(AssistantThreads threads, AssistantOrchestrator orchestrator,
            AssistantRunEvents events) {
        this.threads = threads;
        this.orchestrator = orchestrator;
        this.events = events;
    }

    @PostMapping("/threads")
    public ResponseEntity<ApiResponse<ThreadDetail>> create(@AuthenticationPrincipal User user,
            @RequestBody(required = false) CreateThreadRequest request) {
        return ResponseEntity.ok(ApiResponse.success(view(threads.create(user, request == null ? null : request.title()))));
    }

    /** Los atajos que ofrece el chat; los define el catalogo MCP, no la web. */
    @GetMapping("/starters")
    public ResponseEntity<ApiResponse<List<AssistantOrchestrator.Starter>>> starters(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(orchestrator.starters(user)));
    }

    @GetMapping("/threads")
    public ResponseEntity<ApiResponse<List<ThreadSummary>>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(threads.list(user).stream().map(this::summary).toList()));
    }

    @GetMapping("/threads/{id}")
    public ResponseEntity<ApiResponse<ThreadDetail>> detail(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(view(threads.detail(user, id))));
    }

    @PostMapping("/threads/{id}/close")
    public ResponseEntity<ApiResponse<ThreadDetail>> close(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(view(threads.close(user, id))));
    }

    @PostMapping("/threads/{id}/resume")
    public ResponseEntity<ApiResponse<ThreadDetail>> resume(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(view(threads.resume(user, id))));
    }

    @PostMapping("/threads/{id}/messages")
    public ResponseEntity<ApiResponse<ThreadDetail>> send(@AuthenticationPrincipal User user,
            @PathVariable UUID id, @RequestBody SendMessageRequest request) {
        AssistantOrchestrator.Result result = orchestrator.send(user, id,
                new AssistantOrchestrator.Send(request.content(), request.attachmentIds(), request.currentPath(),
                        request.idempotencyKey(), request.promptName()));
        return ResponseEntity.ok(ApiResponse.success(view(result.detail(), result.confirmationToken())));
    }

    @PostMapping("/runs/{id}/confirm")
    public ResponseEntity<ApiResponse<ThreadDetail>> confirm(@AuthenticationPrincipal User user,
            @PathVariable UUID id, @RequestBody ConfirmRequest request) {
        AssistantOrchestrator.Result result = orchestrator.confirm(user, id, request.token());
        return ResponseEntity.ok(ApiResponse.success(view(result.detail(), result.confirmationToken())));
    }

    @PostMapping("/runs/{id}/confirmation-token")
    public ResponseEntity<ApiResponse<ThreadDetail>> renewConfirmation(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        AssistantOrchestrator.Result result = orchestrator.renewConfirmation(user, id);
        return ResponseEntity.ok(ApiResponse.success(view(result.detail(), result.confirmationToken())));
    }

    @PostMapping("/runs/{id}/cancel")
    public ResponseEntity<ApiResponse<ThreadDetail>> cancel(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(view(orchestrator.cancel(user, id))));
    }

    @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal User user, @PathVariable UUID id,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long after = 0;
        if (lastEventId != null && lastEventId.matches("\\d+")) after = Long.parseLong(lastEventId);
        return events.subscribe(user, id, after);
    }

    @DeleteMapping("/threads/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        threads.delete(user, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private ThreadSummary summary(AssistantThread thread) {
        return new ThreadSummary(thread.getId(), thread.getTitle(), thread.getUpdatedAt(), thread.getClosedAt() != null);
    }

    private ThreadDetail view(AssistantThreads.Detail detail) {
        return view(detail, null);
    }

    private ThreadDetail view(AssistantThreads.Detail detail, String confirmationToken) {
        AssistantThread thread = detail.thread();
        return new ThreadDetail(thread.getId(), thread.getTitle(), thread.getSummary(), thread.getCampaignId(),
                thread.getCreatedAt(), thread.getUpdatedAt(), thread.getClosedAt(),
                detail.messages().stream().map(this::message).toList(),
                detail.runs().stream().map(this::run).toList(),
                detail.toolCalls().stream().map(this::toolCall).toList(), confirmationToken);
    }

    private MessageView message(AssistantMessage message) {
        return new MessageView(message.getId(), message.getRunId(), message.getRole(), message.getContent(),
                message.getAttachmentsJson(), message.getArtifactsJson(), message.getCreatedAt());
    }

    private RunView run(AssistantRun run) {
        return new RunView(run.getId(), run.getStatus().name(), run.getIntent(), run.getErrorCode(),
                run.getErrorMessage(), run.getCreatedAt(), run.getFinishedAt());
    }

    private ToolCallView toolCall(AssistantToolCall call) {
        return new ToolCallView(call.getId(), call.getRunId(), call.getToolName(), call.getRiskLevel(),
                call.getPermission(), call.getStatus().name(), call.getResultSummary(), call.getAffectedEntityType(),
                call.getAffectedEntityId(), call.getCreatedAt());
    }
}
