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

    /** Lo que se lee mientras la conversacion no tiene nombre propio. */
    public static final String NUEVA = "Nueva conversacion";
    public static final String GLOBAL = "Memoria del negocio";

    /**
     * Un titulo mas largo no cabe en la lista lateral: se corta igual al dibujarse, asi
     * que dejar escribir de mas solo enganaria sobre lo que se va a ver.
     */
    public static final int MAX_TITULO = 80;

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
        AssistantThread existing = threads
                .findFirstByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(workspaceId)
                .orElse(null);
        if (existing != null) {
            if (existing.getClosedAt() != null) {
                existing.setClosedAt(null);
                existing.setUpdatedAt(LocalDateTime.now());
                threads.save(existing);
            }
            return detail(user, existing.getId());
        }
        LocalDateTime now = LocalDateTime.now();
        AssistantThread thread = threads.save(AssistantThread.builder()
                .workspaceId(workspaceId)
                .createdBy(user.getId())
                .title(GLOBAL)
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
        return threads.findFirstByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(workspaceOf(user))
                .map(List::of)
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public Detail detail(User user, UUID threadId) {
        return detail(user, threadId, false);
    }

    /**
     * @param completo también lo anterior al último "empezar de nuevo": es lo que pide
     *                 "Ver conversación anterior". Solo para leer; al modelo nunca le llega.
     */
    @Transactional(readOnly = true)
    public Detail detail(User user, UUID threadId, boolean completo) {
        AssistantThread thread = owned(user, threadId);
        LocalDateTime desde = completo ? null : thread.getReiniciadoEn();
        List<AssistantRun> threadRuns = runs.findByThreadIdOrderByCreatedAtAsc(threadId).stream()
                .filter(run -> desde == null || run.getCreatedAt().isAfter(desde))
                .toList();
        List<AssistantToolCall> calls = threadRuns.stream()
                .flatMap(run -> toolCalls.findByRunIdOrderByCreatedAtAsc(run.getId()).stream())
                .toList();
        List<AssistantMessage> todos = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
        return new Detail(thread, completo ? todos : visibles(thread, todos), threadRuns, calls);
    }

    /** Los mensajes desde el último "empezar de nuevo"; todos, si nunca se reinició. */
    public static List<AssistantMessage> visibles(AssistantThread thread, List<AssistantMessage> todos) {
        LocalDateTime desde = thread == null ? null : thread.getReiniciadoEn();
        if (desde == null) {
            return todos;
        }
        // Estricto: lo del mismo instante del corte es de antes (el saludo nuevo va justo despues).
        return todos.stream().filter(m -> m.getCreatedAt().isAfter(desde)).toList();
    }

    /**
     * Renombra la conversacion. El titulo automatico sale del primer mensaje y rara vez
     * dice de que iba, asi que ponerle nombre es lo que hace util la lista con el tiempo.
     */
    @Transactional
    public Detail rename(User user, UUID threadId, String requestedTitle) {
        AssistantThread thread = owned(user, threadId);
        String clean = title(requestedTitle);
        if (NUEVA.equals(clean)) {
            throw new IllegalArgumentException("Escribe un nombre para la conversacion.");
        }
        thread.setTitle(clean);
        thread.setUpdatedAt(LocalDateTime.now());
        threads.save(thread);
        return detail(user, threadId);
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
            return NUEVA;
        }
        return clean.length() <= MAX_TITULO ? clean : clean.substring(0, MAX_TITULO);
    }
}
