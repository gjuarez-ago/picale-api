package com.metricol.api.service.assistant;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.AssistantMessage;
import com.metricol.api.entity.AssistantRun;
import com.metricol.api.entity.AssistantThread;
import com.metricol.api.entity.AssistantToolCall;
import com.metricol.api.entity.User;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.AssistantMessageRepository;
import com.metricol.api.repository.AssistantRunRepository;
import com.metricol.api.repository.AssistantThreadRepository;
import com.metricol.api.repository.AssistantToolCallRepository;

/** Persistencia y pertenencia de las conversaciones del asistente operativo. */
@Service
public class AssistantThreads {

    public static final String WELCOME =
            "Estoy listo. Puedo revisar tu cuenta, preparar contenido y ayudarte a decidir que hacer despues.";

    public record Detail(AssistantThread thread, List<AssistantMessage> messages,
            List<AssistantRun> runs, List<AssistantToolCall> toolCalls) {
    }

    private final AssistantThreadRepository threads;
    private final AssistantMessageRepository messages;
    private final AssistantRunRepository runs;
    private final AssistantToolCallRepository toolCalls;

    public AssistantThreads(AssistantThreadRepository threads, AssistantMessageRepository messages,
            AssistantRunRepository runs, AssistantToolCallRepository toolCalls) {
        this.threads = threads;
        this.messages = messages;
        this.runs = runs;
        this.toolCalls = toolCalls;
    }

    @Transactional
    public Detail create(User user, String requestedTitle) {
        UUID workspaceId = workspaceOf(user);
        LocalDateTime now = LocalDateTime.now();
        AssistantThread thread = threads.save(AssistantThread.builder()
                .workspaceId(workspaceId)
                .createdBy(user.getId())
                .title(title(requestedTitle))
                .createdAt(now)
                .updatedAt(now)
                .build());
        messages.save(AssistantMessage.builder()
                .threadId(thread.getId())
                .role("ASSISTANT")
                .content(WELCOME)
                .createdAt(now)
                .build());
        return detail(user, thread.getId());
    }

    @Transactional(readOnly = true)
    public List<AssistantThread> list(User user) {
        return threads.findByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(workspaceOf(user));
    }

    @Transactional(readOnly = true)
    public Detail detail(User user, UUID threadId) {
        AssistantThread thread = owned(user, threadId);
        List<AssistantRun> threadRuns = runs.findByThreadIdOrderByCreatedAtAsc(threadId);
        List<AssistantToolCall> calls = threadRuns.stream()
                .flatMap(run -> toolCalls.findByRunIdOrderByCreatedAtAsc(run.getId()).stream())
                .toList();
        return new Detail(thread, messages.findByThreadIdOrderByCreatedAtAsc(threadId), threadRuns, calls);
    }

    @Transactional
    public Detail close(User user, UUID threadId) {
        AssistantThread thread = owned(user, threadId);
        if (thread.getClosedAt() == null) {
            thread.setClosedAt(LocalDateTime.now());
            thread.setUpdatedAt(LocalDateTime.now());
            threads.save(thread);
        }
        return detail(user, threadId);
    }

    @Transactional
    public Detail resume(User user, UUID threadId) {
        AssistantThread thread = owned(user, threadId);
        if (thread.getClosedAt() != null) {
            thread.setClosedAt(null);
            thread.setUpdatedAt(LocalDateTime.now());
            threads.save(thread);
        }
        return detail(user, threadId);
    }

    @Transactional
    public void delete(User user, UUID threadId) {
        AssistantThread thread = owned(user, threadId);
        if (thread.getDeletedAt() == null) {
            thread.setDeletedAt(LocalDateTime.now());
            thread.setUpdatedAt(LocalDateTime.now());
            threads.save(thread);
        }
    }

    private AssistantThread owned(User user, UUID threadId) {
        UUID workspaceId = workspaceOf(user);
        return threads.findById(threadId)
                .filter(thread -> workspaceId.equals(thread.getWorkspaceId()) && thread.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Esa conversacion ya no esta disponible."));
    }

    private static UUID workspaceOf(User user) {
        if (user == null || user.getWorkspace() == null || user.getWorkspace().getId() == null) {
            throw new IllegalArgumentException("Elige un espacio de trabajo primero.");
        }
        return user.getWorkspace().getId();
    }

    private static String title(String value) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) {
            return "Nueva conversacion";
        }
        return clean.length() <= 120 ? clean : clean.substring(0, 120);
    }
}
