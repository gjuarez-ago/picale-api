package com.metricol.api.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.AssistantMessage;
import com.metricol.api.entity.AssistantThread;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.AssistantMessageRepository;
import com.metricol.api.repository.AssistantRunRepository;
import com.metricol.api.repository.AssistantThreadRepository;
import com.metricol.api.repository.AssistantToolCallRepository;

class AssistantThreadsTest {

    private final AssistantThreadRepository threads = mock(AssistantThreadRepository.class);
    private final AssistantMessageRepository messages = mock(AssistantMessageRepository.class);
    private final AssistantRunRepository runs = mock(AssistantRunRepository.class);
    private final AssistantToolCallRepository calls = mock(AssistantToolCallRepository.class);
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private AssistantThreads service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AssistantThreads(threads, messages, runs, calls);
        user = User.builder().id(userId).workspace(Workspace.builder().id(workspaceId).build()).build();
        when(messages.findByThreadIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(runs.findByThreadIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    @Test
    void creaElHiloEnElWorkspaceYGuardaLaBienvenida() {
        when(threads.save(any())).thenAnswer(invocation -> {
            AssistantThread thread = invocation.getArgument(0);
            thread.setId(UUID.randomUUID());
            return thread;
        });
        when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(threads.findById(any())).thenAnswer(invocation -> Optional.of(AssistantThread.builder()
                .id(invocation.getArgument(0)).workspaceId(workspaceId).createdBy(userId).title("Resultados")
                .createdAt(java.time.LocalDateTime.now()).updatedAt(java.time.LocalDateTime.now()).build()));

        AssistantThreads.Detail detail = service.create(user, "  Resultados  ");

        assertThat(detail.thread().getWorkspaceId()).isEqualTo(workspaceId);
        verify(threads).save(org.mockito.ArgumentMatchers.argThat(thread ->
                workspaceId.equals(thread.getWorkspaceId()) && AssistantThreads.GLOBAL.equals(thread.getTitle())));
        verify(messages).save(org.mockito.ArgumentMatchers.argThat(message ->
                "ASSISTANT".equals(message.getRole()) && AssistantThreads.WELCOME.equals(message.getContent())));
    }

    @Test
    void abrirDeNuevoDevuelveLaConversacionGlobalSinCrearOtra() {
        UUID threadId = UUID.randomUUID();
        AssistantThread existing = AssistantThread.builder().id(threadId).workspaceId(workspaceId)
                .createdBy(userId).title(AssistantThreads.GLOBAL).closedAt(java.time.LocalDateTime.now())
                .createdAt(java.time.LocalDateTime.now()).updatedAt(java.time.LocalDateTime.now()).build();
        when(threads.findFirstByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(Optional.of(existing));
        when(threads.findById(threadId)).thenReturn(Optional.of(existing));
        when(threads.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AssistantThreads.Detail detail = service.create(user, "Un titulo que ya no crea otro hilo");

        assertThat(detail.thread().getId()).isEqualTo(threadId);
        assertThat(detail.thread().getClosedAt()).isNull();
        verify(messages, never()).save(any());
    }

    @Test
    void laListaSoloExponeLaContinuidadGlobalMasReciente() {
        AssistantThread current = AssistantThread.builder().id(UUID.randomUUID()).workspaceId(workspaceId)
                .title(AssistantThreads.GLOBAL).build();
        when(threads.findFirstByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(Optional.of(current));

        assertThat(service.list(user)).containsExactly(current);
    }

    @Test
    void renombraRecortandoAlLargoQueCabeEnLaLista() {
        UUID threadId = UUID.randomUUID();
        AssistantThread thread = AssistantThread.builder().id(threadId).workspaceId(workspaceId)
                .createdBy(userId).title(AssistantThreads.NUEVA).build();
        when(threads.findById(threadId)).thenReturn(Optional.of(thread));
        when(threads.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.rename(user, threadId, "  Campaña   de   diciembre  ");
        assertThat(thread.getTitle()).isEqualTo("Campaña de diciembre");

        service.rename(user, threadId, "x".repeat(AssistantThreads.MAX_TITULO + 40));
        assertThat(thread.getTitle()).hasSize(AssistantThreads.MAX_TITULO);
    }

    @Test
    void noAceptaUnNombreVacio() {
        UUID threadId = UUID.randomUUID();
        when(threads.findById(threadId)).thenReturn(Optional.of(AssistantThread.builder()
                .id(threadId).workspaceId(workspaceId).createdBy(userId).title("Algo").build()));

        assertThatThrownBy(() -> service.rename(user, threadId, "   "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Escribe un nombre");
    }

    @Test
    void noRenombraUnHiloDeOtroWorkspace() {
        UUID threadId = UUID.randomUUID();
        when(threads.findById(threadId)).thenReturn(Optional.of(AssistantThread.builder()
                .id(threadId).workspaceId(UUID.randomUUID()).createdBy(userId).title("Ajeno").build()));

        assertThatThrownBy(() -> service.rename(user, threadId, "Mio"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void noExponeUnHiloDeOtroWorkspace() {
        UUID threadId = UUID.randomUUID();
        when(threads.findById(threadId)).thenReturn(Optional.of(AssistantThread.builder()
                .id(threadId).workspaceId(UUID.randomUUID()).createdBy(userId).title("Ajeno").build()));

        assertThatThrownBy(() -> service.detail(user, threadId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
