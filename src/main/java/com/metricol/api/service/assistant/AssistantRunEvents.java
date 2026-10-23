package com.metricol.api.service.assistant;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.AssistantRun;
import com.metricol.api.entity.AssistantRunEvent;
import com.metricol.api.entity.User;
import com.metricol.api.enums.AssistantRunStatus;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.AssistantRunEventRepository;
import com.metricol.api.repository.AssistantRunRepository;

/** Persiste y distribuye el avance; la base permite recuperar eventos perdidos. */
@Service
public class AssistantRunEvents {

    private final AssistantRunEventRepository events;
    private final AssistantRunRepository runs;
    private final ObjectMapper json;
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public AssistantRunEvents(AssistantRunEventRepository events, AssistantRunRepository runs, ObjectMapper json) {
        this.events = events;
        this.runs = runs;
        this.json = json;
    }

    @Transactional
    public AssistantRunEvent emit(AssistantRun run, String type, JsonNode payload) {
        long sequence = events.countByRunId(run.getId()) + 1;
        AssistantRunEvent event = events.save(AssistantRunEvent.builder()
                .workspaceId(run.getWorkspaceId()).runId(run.getId()).sequence(sequence).type(type)
                .payloadJson(payload == null ? "{}" : payload.toString()).createdAt(LocalDateTime.now()).build());
        publish(event);
        if (terminal(run.getStatus())) complete(run.getId());
        return event;
    }

    @Transactional(readOnly = true)
    public long activeMillis(UUID runId) {
        return events.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(runId, 0).stream()
                .filter(event -> "run.segment.completed".equals(event.getType()))
                .mapToLong(event -> Math.max(0, parse(event.getPayloadJson()).path("activeMs").asLong(0))).sum();
    }

    @Transactional(readOnly = true)
    public SseEmitter subscribe(User user, UUID runId, long afterSequence) {
        AssistantRun run = owned(user, runId);
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        List<AssistantRunEvent> replay = events.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(runId,
                Math.max(0, afterSequence));
        try {
            for (AssistantRunEvent event : replay) send(emitter, event);
            if (terminal(run.getStatus())) {
                emitter.complete();
                return emitter;
            }
        } catch (IOException error) {
            emitter.completeWithError(error);
            return emitter;
        }

        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> remove(runId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(ignored -> remove.run());
        return emitter;
    }

    private void publish(AssistantRunEvent event) {
        var list = subscribers.get(event.getRunId());
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try { send(emitter, event); }
            catch (IOException error) {
                remove(event.getRunId(), emitter);
                emitter.completeWithError(error);
            }
        }
    }

    private void complete(UUID runId) {
        var list = subscribers.remove(runId);
        if (list != null) list.forEach(SseEmitter::complete);
    }

    private void remove(UUID runId, SseEmitter emitter) {
        var list = subscribers.get(runId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) subscribers.remove(runId, list);
    }

    private void send(SseEmitter emitter, AssistantRunEvent event) throws IOException {
        emitter.send(SseEmitter.event().id(Long.toString(event.getSequence())).name(event.getType())
                .data(parse(event.getPayloadJson())));
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (Exception error) { return json.createObjectNode(); }
    }

    private AssistantRun owned(User user, UUID runId) {
        UUID workspaceId = user == null || user.getWorkspace() == null ? null : user.getWorkspace().getId();
        return runs.findById(runId).filter(run -> workspaceId != null && workspaceId.equals(run.getWorkspaceId())
                        && user.getId().equals(run.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Esa ejecucion no esta disponible."));
    }

    private static boolean terminal(AssistantRunStatus status) {
        return status == AssistantRunStatus.COMPLETED || status == AssistantRunStatus.FAILED
                || status == AssistantRunStatus.CANCELLED;
    }
}
