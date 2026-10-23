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
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.AssistantRunStatus;
import com.metricol.api.enums.AssistantToolCallStatus;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
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

    private AssistantOrchestrator.Result prepareGenerationQuote() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[{\"name\":\"generar_contenido\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"picale/policy\":{\"riskLevel\":1,\"permission\":\"POST_CREATE\",\"confirmation\":\"cost_notice\"}}}]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("Inventé un coste de 999", List.of(
                new AssistantLlmClient.ToolCall("quote", "generar_contenido", json.createObjectNode().put("brief", "Solo luz"))),
                json.createObjectNode().put("role", "assistant"), "test", 1, 1));
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("Retoca la foto", List.of(), "/panel/asistente", "quote-test"));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        return sent;
    }

    @Test void confirmationShowsVerifiedCostAndDoesNotExposePrivateQuote() throws Exception {
        prepareGenerationQuote();
        var message = history.get(history.size() - 1);
        assertThat(message.getContent()).contains("5 créditos").doesNotContain("999");
        assertThat(message.getArtifactsJson()).doesNotContain("_picaleQuotedCredits");
        assertThat(savedCalls.get(0).getArgumentsJson()).contains("_picaleQuotedCredits");
        verify(session, never()).call(eq("generar_contenido"), any());
    }

    @Test void costIncreaseAfterApprovalNeverStartsGeneration() throws Exception {
        var sent = prepareGenerationQuote();
        when(session.call(eq("validar_generacion"), any())).thenReturn(json.createObjectNode()
                .set("structuredContent", json.createObjectNode().put("valido", true).put("creditos", 6)));
        var token = service.renewConfirmation(user, sent.runId()).confirmationToken();
        service.confirm(user, sent.runId(), token);
        verify(session, never()).call(eq("generar_contenido"), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.FAILED);
        assertThat(savedRun.getErrorMessage()).contains("El coste aumentó");
    }

    @Test void sameApprovedCostCreatesOneJobWithoutSendingPrivateFields() throws Exception {
        var sent = prepareGenerationQuote();
        when(session.call(eq("generar_contenido"), any())).thenReturn(json.createObjectNode()
                .set("structuredContent", json.createObjectNode().put("trabajo_id", UUID.randomUUID().toString())));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("Trabajo iniciado", List.of(), json.createObjectNode(), "test", 1, 1));
        var token = service.renewConfirmation(user, sent.runId()).confirmationToken();
        service.confirm(user, sent.runId(), token);
        verify(session, org.mockito.Mockito.times(1)).call(eq("generar_contenido"), org.mockito.ArgumentMatchers.argThat(args -> !args.has("_picaleQuotedCredits")));
        verify(llm, org.mockito.Mockito.times(1)).complete(any(), any()); // Solo preparó la propuesta; no espera a la imagen.
        verify(session, never()).call(eq("estado_generacion"), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
        assertThatThrownBy(() -> service.confirm(user, sent.runId(), token)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void linkageFailureAfterStartingGenerationKeepsReceiptAndDoesNotRestoreConfirmationToken() throws Exception {
        var sent = prepareGenerationQuote();
        String jobId = UUID.randomUUID().toString();
        when(session.call(eq("generar_contenido"), any())).thenReturn(json.createObjectNode()
                .set("structuredContent", json.createObjectNode().put("trabajo_id", jobId)));
        org.mockito.Mockito.doAnswer(invocation -> {
            AssistantMessage message = invocation.getArgument(0);
            if (message.getContent() != null && message.getContent().startsWith("El trabajo quedó iniciado"))
                throw new NoClassDefFoundError("missing-presentation-class");
            if (message.getId() == null) message.setId(UUID.randomUUID());
            if (!history.contains(message)) history.add(message);
            return message;
        }).when(messages).save(any());
        var token = service.renewConfirmation(user, sent.runId()).confirmationToken();
        service.confirm(user, sent.runId(), token);
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.FAILED);
        assertThat(savedCalls.get(0).getStatus()).isEqualTo(AssistantToolCallStatus.COMPLETED);
        assertThat(savedCalls.get(0).getConfirmationTokenHash()).isNull();
        assertThat(history).anySatisfy(message -> assertThat(message.getArtifactsJson()).contains(jobId));
        verify(session, org.mockito.Mockito.times(1)).call(eq("generar_contenido"), any());
    }

    @Test void refreshesOnlyTheOwnedJobWithoutCallingTheModelOrStartingAnotherGeneration() throws Exception {
        String jobId = UUID.randomUUID().toString();
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("Prueba", List.of(), "/panel/asistente", "poll-only"));
        savedRun.setStatus(AssistantRunStatus.COMPLETED);
        history.add(AssistantMessage.builder().id(UUID.randomUUID()).threadId(threadId).runId(sent.runId()).role("ASSISTANT")
                .artifactsJson("[{\"type\":\"generation\",\"jobId\":\"" + jobId + "\",\"status\":\"QUEUED\",\"versions\":[]}]").build());
        when(session.call(eq("estado_generacion"), any())).thenReturn(json.createObjectNode()
                .set("structuredContent", json.createObjectNode().put("estado", "CREATING")));
        service.refreshGeneration(user, sent.runId());
        service.refreshGeneration(user, sent.runId());
        verify(session, org.mockito.Mockito.times(2)).call(eq("estado_generacion"), org.mockito.ArgumentMatchers.argThat(
                args -> jobId.equals(args.path("trabajo_id").asText()) && args.path("esperar_segundos").asInt(-1) == 0));
        verify(session, never()).call(eq("generar_contenido"), any());
        org.mockito.Mockito.verifyNoInteractions(llm);
        assertThat(history.stream().filter(m -> m.getArtifactsJson() != null && m.getArtifactsJson().contains(jobId))).hasSize(1);
        assertThat(history.get(history.size() - 1).getArtifactsJson()).contains("CREATING");
    }

    @Test void keepsReadyGalleryWithoutConsultingAnExpiredInMemoryJob() {
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("Prueba", List.of(), "/panel/asistente", "ready-preserved"));
        savedRun.setStatus(AssistantRunStatus.COMPLETED);
        history.add(AssistantMessage.builder().id(UUID.randomUUID()).threadId(threadId).runId(sent.runId()).role("ASSISTANT")
                .artifactsJson("[{\"type\":\"generation\",\"jobId\":\"" + UUID.randomUUID() + "\",\"status\":\"READY\",\"versions\":[]}]").build());
        var oldAck = AssistantMessage.builder().id(UUID.randomUUID()).threadId(threadId).runId(sent.runId()).role("ASSISTANT")
                .content("La creación está en proceso. La imagen aparecerá aquí cuando esté lista. No se publicó ni programó.").build();
        history.add(oldAck);
        service.refreshGeneration(user, sent.runId());
        assertThat(oldAck.getContent()).isEqualTo("El trabajo quedó iniciado. Su estado y resultado se muestran en la tarjeta. No se publicó ni programó.");
        verify(session, never()).call(any(), any());
        org.mockito.Mockito.verifyNoInteractions(llm);
        var other = User.builder().id(UUID.randomUUID()).workspace(user.getWorkspace()).build();
        assertThatThrownBy(() -> service.refreshGeneration(other, sent.runId()))
                .isInstanceOf(com.metricol.api.exception.ResourceNotFoundException.class);
    }

    @Test void identicalReadsUseOneExternalCallAndDoNotRepeatTheLargePayload() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[{\"name\":\"panel\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"picale/policy\":{\"riskLevel\":0,\"permission\":\"MEMBERSHIP\",\"confirmation\":\"none\"}}}]"));
        when(session.call(eq("panel"), any())).thenReturn(json.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"Dato real\"}]}"));
        var empty = json.createObjectNode();
        when(llm.complete(any(), any()))
                .thenReturn(new AssistantLlmClient.Turn("", List.of(new AssistantLlmClient.ToolCall("read-1", "panel", empty)), empty, "test", 1, 1))
                .thenReturn(new AssistantLlmClient.Turn("", List.of(new AssistantLlmClient.ToolCall("read-2", "panel", empty)), empty, "test", 1, 1))
                .thenAnswer(invocation -> {
                    assertThat(invocation.getArgument(0).toString()).contains("Lectura idéntica ya disponible");
                    return new AssistantLlmClient.Turn("Listo", List.of(), empty, "test", 1, 1);
                });
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("Cómo vamos", List.of(), "/panel/asistente", "read-once"));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        verify(session, org.mockito.Mockito.times(1)).call(eq("panel"), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
    }

    @Test void invalidGenerationIsCheckedBeforeConfirmationAndRepeatedFailureStops() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[{\"name\":\"generar_contenido\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"picale/policy\":{\"riskLevel\":1,\"permission\":\"POST_CREATE\",\"confirmation\":\"cost_notice\"}}}]"));
        var invalid = json.createObjectNode().put("isError", true);
        invalid.putArray("content").addObject().put("type", "text").put("text", "Elige una foto");
        when(session.call(eq("validar_generacion"), any())).thenReturn(invalid);
        var args = json.createObjectNode().put("brief", "Mejora la luz");
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("invalid", "generar_contenido", args)),
                json.createObjectNode().put("role", "assistant"), "test", 1, 1));
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("Retoca", List.of(), "/panel/asistente", "invalid-before-cost"));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        verify(session, org.mockito.Mockito.times(1)).call(eq("validar_generacion"), any());
        verify(session, never()).call(eq("generar_contenido"), any());
        verify(events, never()).emit(any(), eq("confirmation.required"), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.FAILED);
        assertThat(savedRun.getErrorMessage()).contains("No lo repetí");
    }

    @Test void workerFailureEndsQueuedTurnAndEmitsVisibleError() {
        var sent = service.send(user, threadId,
                new AssistantOrchestrator.Send("Hola", List.of(), "/panel/asistente", "failed-worker"));
        service.workerFailed(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.FAILED);
        assertThat(savedRun.getFinishedAt()).isNotNull();
        assertThat(savedRun.getErrorMessage()).contains("Tu mensaje sigue guardado");
        verify(events).emit(eq(savedRun), eq("run.failed"), any());
        verifyNoModelOrToolRetry();
    }

    @Test void empezarDeNuevoParaElTurnoEnColaYNoOlvidaLaMemoria() {
        thread.setSummary("Banquetera: vende pasteles; Facebook conectado.");
        var sent = service.send(user, threadId,
                new AssistantOrchestrator.Send("Hola", List.of(), "/panel/asistente", "reinicio-1"));
        when(runs.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of(savedRun));

        service.empezarDeNuevo(user, threadId);

        // La conversación queda limpia desde aquí, pero la memoria del negocio sigue.
        assertThat(thread.getReiniciadoEn()).isNotNull();
        assertThat(thread.getSummary()).isEqualTo("Banquetera: vende pasteles; Facebook conectado.");
        assertThat(AssistantThreads.visibles(thread, history)).extracting(AssistantMessage::getContent)
                .containsExactly("Empezamos de nuevo. Sigo recordando lo importante de tu negocio; dime en qué te ayudo.");
        // El turno que estaba en cola ya no arranca: ni modelo ni herramientas.
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.CANCELLED);
        verify(events).emit(eq(savedRun), eq("run.cancelled"), any());
        verifyNoModelOrToolRetry();
    }

    @Test void detenerQuedaEnLaBaseYElTurnoLoRespetaAunqueLoCorraOtroServidor() {
        var paradas = org.mockito.Mockito.mock(com.metricol.api.repository.AssistantRunParadaRepository.class);
        service.setParadas(paradas);
        var sent = service.send(user, threadId,
                new AssistantOrchestrator.Send("Hola", List.of(), "/panel/asistente", "detener-1"));
        when(runs.findById(sent.runId())).thenReturn(Optional.of(savedRun));

        service.detener(user, sent.runId());

        verify(paradas).save(org.mockito.ArgumentMatchers.argThat(p -> p.getRunId().equals(sent.runId())));
        // El que corre el turno lo lee de la base, no de su memoria.
        when(paradas.existsById(sent.runId())).thenReturn(true);
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.CANCELLED);
        verify(paradas).deleteById(sent.runId());
        verifyNoModelOrToolRetry();
    }

    @Test void workerFailureDoesNotOverwriteCompletedOrConfirmationTurn() {
        var sent = service.send(user, threadId,
                new AssistantOrchestrator.Send("Hola", List.of(), "/panel/asistente", "ended-worker"));
        for (var status : List.of(AssistantRunStatus.COMPLETED, AssistantRunStatus.WAITING_CONFIRMATION,
                AssistantRunStatus.CANCELLED, AssistantRunStatus.FAILED)) {
            savedRun.setStatus(status);
            service.workerFailed(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
            assertThat(savedRun.getStatus()).isEqualTo(status);
        }
        verify(events, never()).emit(any(), eq("run.failed"), any());
    }

    @Test void workerFailureCannotAffectAnotherWorkspaceOrUser() {
        var sent = service.send(user, threadId,
                new AssistantOrchestrator.Send("Hola", List.of(), "/panel/asistente", "tenant-worker"));
        service.workerFailed(new AssistantRunDispatcher.Requested(sent.runId(), userId, UUID.randomUUID()));
        service.workerFailed(new AssistantRunDispatcher.Requested(sent.runId(), UUID.randomUUID(), workspaceId));
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.QUEUED);
    }

    private void verifyNoModelOrToolRetry() {
        org.mockito.Mockito.verifyNoInteractions(llm, mcp);
    }

    @Test void confirmarSoloElFormularioDeBriefNoAutorizaElCosteAunqueLaPoliticaSeaDeNivelUno() throws Exception {
        String literal = "Mejora la iluminación sin cambiar ingredientes.";
        history.add(AssistantMessage.builder().id(UUID.randomUUID()).threadId(threadId).role("ASSISTANT")
                .content("Revisa el prompt").artifactsJson("[{\"type\":\"form\",\"fields\":[{\"name\":\"brief\",\"type\":\"texto\"}]}]").build());
        when(session.tools()).thenReturn(json.readTree("[{\"name\":\"generar_contenido\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"picale/policy\":{\"riskLevel\":1,\"permission\":\"POST_CREATE\",\"confirmation\":\"cost_notice\"}}}]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-cost", "generar_contenido", json.createObjectNode().put("brief", literal))),
                json.createObjectNode().put("role", "assistant"), "test", 1, 1));
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("Instrucciones: " + literal,
                List.of(), "/panel/asistente", "brief-cost", null, java.util.Map.of("brief", literal)));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        assertThat(savedCalls.get(0).getStatus()).isEqualTo(AssistantToolCallStatus.WAITING_CONFIRMATION);
        verify(session, never()).call(eq("generar_contenido"), any());
    }

    @Test void aclararElNombreNoPermiteResumirElPromptLargoDeLaFotoAnterior() throws Exception {
        String original = "Conserva el platillo y sus ingredientes. " + "a".repeat(3500);
        history.add(AssistantMessage.builder().id(UUID.randomUUID()).threadId(threadId).role("USER").content(original)
                .attachmentsJson("[{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"IMAGE\"}]").build());
        when(session.tools()).thenReturn(json.readTree("[{\"name\":\"generar_contenido\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"picale/policy\":{\"riskLevel\":3,\"permission\":\"POST_CREATE\",\"confirmation\":\"cost_notice\"}}}]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-name", "generar_contenido", json.createObjectNode().put("brief", "Un anuncio resumido"))),
                json.createObjectNode().put("role", "assistant"), "test", 1, 1));
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("Sí, es el negocio actual", List.of(), "/panel/asistente", "brief-name"));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        verify(session, never()).call(eq("generar_contenido"), any());
        assertThat(json.readTree(history.get(history.size() - 1).getArtifactsJson()).get(0).path("fields").get(0).path("value").asText()).isEqualTo(original);
    }

    @Test void unPromptExcedidoVuelveComoFormularioCompletoSinGenerarNiCobrar() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[{\"name\":\"generar_contenido\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"picale/policy\":{\"riskLevel\":3,\"permission\":\"POST_CREATE\",\"confirmation\":\"cost_notice\"}}}]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-long", "generar_contenido", json.createObjectNode().put("brief", "Resumen que no se debe usar"))),
                json.createObjectNode().put("role", "assistant"), "test", 1, 1));
        String literal = "No cambies ingredientes ni porciones. " + "a".repeat(3600);
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send(
                "Modo de imagen: RETOQUE.\nBrief de la pieza (literal, confirmado por mí):\n" + literal, List.of(), "/panel/asistente", "brief-long"));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        verify(session, never()).call(eq("generar_contenido"), any());
        var answer = history.get(history.size() - 1);
        var field = json.readTree(answer.getArtifactsJson()).get(0).path("fields").get(0);
        assertThat(field.path("maxLength").asInt()).isEqualTo(1500);
        assertThat(field.path("value").asText()).isEqualTo(literal);
    }

    @Test void elBriefLiteralDelUsuarioNoSeSustituyePorElResumenDelModeloYMantieneConfirmacion() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[{\"name\":\"generar_contenido\",\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"picale/policy\":{\"riskLevel\":3,\"permission\":\"POST_CREATE\",\"confirmation\":\"cost_notice\"}}}]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-brief", "generar_contenido", json.createObjectNode().put("brief", "Haz un anuncio"))),
                json.createObjectNode().put("role", "assistant"), "test", 1, 1));
        String literal = "Solo mejora la luz. Conserva exactamente los alimentos, ingredientes y ambiente; sin texto.";
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send(
                "Modo de imagen: RETOQUE.\nBrief de la pieza (literal, confirmado por mí):\n" + literal, List.of(), "/panel/asistente", "brief-literal"));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        var call = savedCalls.get(0);
        assertThat(json.readTree(call.getArgumentsJson()).path("brief").asText()).isEqualTo(literal);
        assertThat(json.readTree(call.getArgumentsJson()).path("modo").asText()).isEqualTo("RETOQUE");
        assertThat(call.getStatus()).isEqualTo(AssistantToolCallStatus.WAITING_CONFIRMATION);
        verify(session, never()).call(eq("generar_contenido"), any());
    }

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
            if (!history.contains(message)) history.add(message);
            return message;
        });
        when(messages.findByThreadIdOrderByCreatedAtAsc(threadId)).thenAnswer(invocation -> List.copyOf(history));
        when(threads.findById(threadId)).thenReturn(Optional.of(thread));
        when(threads.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(permissions.permisosDe(user)).thenReturn(EnumSet.allOf(Permission.class));
        when(mcp.open(eq(user), eq(threadId), any(), any())).thenReturn(session);
        when(session.call(eq("validar_generacion"), any())).thenReturn(json.createObjectNode()
                .set("structuredContent", json.createObjectNode().put("valido", true).put("creditos", 5)));
        when(users.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void ejecutaLecturaYEntregaRespuestaSinReciboTecnico() throws Exception {
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
        AssistantMessage answer = history.stream()
                .filter(message -> "Tus publicaciones mejoraron.".equals(message.getContent()))
                .findFirst().orElseThrow();
        assertThat(answer.getArtifactsJson()).isNull();
        assertThat(savedCalls).singleElement().satisfies(call ->
                assertThat(call.getResultSummary()).isEqualTo("resultado real"));
        assertThat(thread.getSummary()).contains("Dato consultado").contains("resultado real");
    }

    @Test
    void usaLaMemoriaComoContextoHistoricoSinConfundirlaConElEstadoActual() throws Exception {
        thread.setSummary("- [2026-10-15] Acción realizada: se creó el borrador semanal.");
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("Lo verifico contigo.", List.of(),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 5, 1));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("¿Qué sigue?", List.of(), "/panel", "message-memory"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        verify(llm).complete(org.mockito.ArgumentMatchers.argThat(messages ->
                messages.get(0).path("content").asText().contains("Memoria operativa verificada")
                        && messages.get(0).path("content").asText().contains("borrador semanal")
                        && messages.get(0).path("content").asText().contains("vuelve a consultar el MCP")), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
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
        assertThat(history.get(history.size() - 1).getArtifactsJson())
                .contains("result").contains("publicada").doesNotContain("\\\"type\\\":\\\"text\\\"");
        assertThatThrownBy(() -> service.confirm(user, result.runId(), renewed.confirmationToken()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ya no espera");
    }

    @Test
    void cierraElTurnoConOpcionesParaTocarSinLlamarAlMcp() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-1", "ofrecer_opciones", json.readTree("""
                        {"pregunta":"¿Cual es el giro?","opciones":[{"etiqueta":"Restaurante"},
                         {"etiqueta":"Tienda de ropa"},{"etiqueta":"Otro"}]}"""))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 7, 2));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Ayudame", List.of(), "/panel", "message-8"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        AssistantMessage last = history.get(history.size() - 1);
        assertThat(last.getContent()).isEqualTo("¿Cual es el giro?");
        assertThat(last.getArtifactsJson()).contains("choices").contains("Restaurante").contains("Tienda de ropa");
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
        // No es una accion: no toca el MCP ni deja recibo de herramienta.
        verify(session, never()).call(any(), any());
        assertThat(savedCalls).isEmpty();
    }

    /**
     * El bucle que se reportó: una confirmación que nadie contestó dejaba el turno en
     * espera para siempre, la web apagaba enviar y solo quedaban los chips. Escribir algo
     * nuevo ahora la deja atrás —sin ejecutarla— y la conversación sigue.
     */
    @Test
    void unMensajeNuevoDejaAtrasLaConfirmacionPendienteSinEjecutarla() throws Exception {
        AssistantRun pendiente = AssistantRun.builder().id(UUID.randomUUID()).workspaceId(workspaceId)
                .threadId(threadId).userId(userId).status(AssistantRunStatus.WAITING_CONFIRMATION)
                .createdAt(LocalDateTime.now().minusHours(3)).build();
        AssistantToolCall esperando = AssistantToolCall.builder().id(UUID.randomUUID()).runId(pendiente.getId())
                .toolName("publicar_ahora").status(AssistantToolCallStatus.WAITING_CONFIRMATION)
                .confirmationTokenHash("hash").build();
        when(runs.findByThreadIdOrderByCreatedAtAsc(threadId)).thenReturn(List.of(pendiente));
        when(runs.findLockedById(pendiente.getId())).thenReturn(Optional.of(pendiente));
        when(calls.findByRunIdOrderByCreatedAtAsc(pendiente.getId())).thenReturn(List.of(esperando));

        service.send(user, threadId, new AssistantOrchestrator.Send("Mejor cambiale la hora", List.of(), "/panel", "nuevo-1"));

        assertThat(pendiente.getStatus()).isEqualTo(AssistantRunStatus.CANCELLED);
        assertThat(esperando.getStatus()).isEqualTo(AssistantToolCallStatus.CANCELLED);
        assertThat(esperando.getConfirmationTokenHash()).isNull();
        assertThat(history).anySatisfy(m -> assertThat(m.getContent()).contains("No hice lo que esperaba tu confirmacion"));
        // Lo pendiente nunca llega a ejecutarse.
        verify(session, never()).call(any(), any());
    }

    /**
     * Se pidió cambiar el giro y salieron tres chips adivinados —banquetes, pastelería,
     * academia—. El giro va siempre con el selector del registro, aunque el modelo lo mande
     * como una lista corta.
     */
    @Test
    void elGiroSaleSiempreConElSelectorDelRegistro() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-1", "pedir_datos", json.readTree("""
                        {"respuesta":"Elige el giro que mejor te describe.",
                         "campos":[{"nombre":"giro","etiqueta":"Giro del negocio","tipo":"opcion",
                                    "opciones":["Banquetes o catering","Pastelería","Academia"],
                                    "valor":"Banquetes o catering"}]}"""))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 7, 2));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("hay que cambiar el giro", List.of(), "/panel", "giro-1"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        var field = json.readTree(history.get(history.size() - 1).getArtifactsJson()).get(0).path("fields").get(0);
        assertThat(field.path("type").asText()).isEqualTo("giro");
        // El catalogo vive en la web; el modelo no lo copia ni lo recorta a tres.
        assertThat(field.path("options")).isEmpty();
        assertThat(field.path("value").asText()).isEqualTo("Banquetes o catering");
    }

    /**
     * Prepara una conversación donde Chispa acaba de enseñar un formulario y la persona lo
     * confirma; el modelo entonces pide la herramienta con los argumentos dados.
     */
    private void responderFormulario(String confirmado, String herramienta, int nivel, String argumentos)
            throws Exception {
        history.add(AssistantMessage.builder().id(UUID.randomUUID()).threadId(threadId).role("ASSISTANT")
                .content("Confirma tu giro.").createdAt(LocalDateTime.now().minusMinutes(1))
                .artifactsJson("[{\"type\":\"form\",\"submit\":\"Confirmar giro\",\"fields\":[{\"name\":\"giro\","
                        + "\"label\":\"Giro principal\",\"type\":\"giro\",\"value\":\"\",\"options\":[]}]}]")
                .build());
        when(session.tools()).thenReturn(json.readTree("[{\"name\":\"" + herramienta + "\",\"title\":\"Actualizar la marca\","
                + "\"description\":\"Para vaciar un campo manda la palabra borrar\","
                + "\"inputSchema\":{\"type\":\"object\"},\"_meta\":{\"picale/policy\":{\"riskLevel\":" + nivel
                + ",\"permission\":\"WORKSPACE_EDIT\",\"confirmation\":\"preview\"}}}]"));
        when(llm.complete(any(), any()))
                .thenReturn(new AssistantLlmClient.Turn("", List.of(new AssistantLlmClient.ToolCall("call-1",
                        herramienta, json.readTree(argumentos))), json.createObjectNode().put("role", "assistant"),
                        "gpt-test", 5, 2))
                .thenReturn(new AssistantLlmClient.Turn("Listo, guardé tu giro.", List.of(),
                        json.createObjectNode().put("role", "assistant"), "gpt-test", 5, 2));
        when(session.call(eq(herramienta), any())).thenReturn(json.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"));
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send(confirmado, List.of(), "/panel", "form-" + herramienta + nivel));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
    }

    /**
     * El bucle de la captura: «Giro principal: Pastelería…» y Chispa contestaba «no se guardará
     * hasta que confirmes» enseñando el mismo selector, una y otra vez. Confirmar en el
     * formulario ya es la confirmación de esos valores exactos.
     */
    @Test
    void guardaLoConfirmadoEnElFormularioSinPreguntarOtraVez() throws Exception {
        responderFormulario("Giro principal: Pastelería, repostería o postres", "actualizar_marca", 2,
                "{\"giro\":\"Pastelería, repostería o postres\"}");

        verify(session).call(eq("actualizar_marca"), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
        assertThat(savedCalls).noneMatch(c -> c.getStatus() == AssistantToolCallStatus.WAITING_CONFIRMATION);
        // La tarjeta de "Hecho" lleva el nombre de la accion, no las instrucciones para el modelo.
        String tarjetas = history.get(history.size() - 1).getArtifactsJson();
        assertThat(tarjetas).contains("Actualizar la marca").doesNotContain("palabra borrar");
    }

    /** Publicar sale a la calle: aunque venga de un formulario, se confirma como siempre. */
    @Test
    void publicarSigueConfirmandoseAunqueVengaDeUnFormulario() throws Exception {
        responderFormulario("Texto: Pasteles para tu boda", "publicar_ahora", 3, "{\"texto\":\"Pasteles para tu boda\"}");

        verify(session, never()).call(eq("publicar_ahora"), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.WAITING_CONFIRMATION);
    }

    /** Si lo que se va a guardar no es lo que confirmó, se pide la confirmación de siempre. */
    @Test
    void siElValorNoEsElConfirmadoPideConfirmacion() throws Exception {
        responderFormulario("Giro principal: Pastelería, repostería o postres", "actualizar_marca", 2,
                "{\"giro\":\"Banquetes o catering\"}");

        verify(session, never()).call(eq("actualizar_marca"), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.WAITING_CONFIRMATION);
    }

    /** El modelo recibe por estructura que el último mensaje responde al formulario. */
    @Test
    void elModeloSabeQueLaRespuestaVieneDelFormulario() throws Exception {
        responderFormulario("Giro principal: Pastelería, repostería o postres", "actualizar_marca", 2,
                "{\"giro\":\"Pastelería, repostería o postres\"}");

        var captor = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
        verify(llm, org.mockito.Mockito.atLeastOnce()).complete(captor.capture(), any());
        assertThat(captor.getAllValues().get(0).toString()).contains("YA CONFIRMO");
    }

    /** La marca con el esquema real de actualizar_marca: texto libre y catalogo. */
    private static final String HERRAMIENTA_MARCA = "[{\"name\":\"actualizar_marca\",\"title\":\"Actualizar la marca\","
            + "\"description\":\"x\",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
            + "\"historia\":{\"type\":\"string\"},\"giro\":{\"type\":\"string\"},\"whatsapp\":{\"type\":\"string\"},"
            + "\"objetivo\":{\"type\":\"string\",\"enum\":[\"MAS_CLIENTES\",\"COMUNIDAD\"]}}},"
            + "\"_meta\":{\"picale/policy\":{\"riskLevel\":2,\"permission\":\"WORKSPACE_EDIT\",\"confirmation\":\"preview\"}}}]";

    private com.fasterxml.jackson.databind.JsonNode guardarDesdeFormulario(java.util.Map<String, String> confirmado,
            String argumentosDelModelo) throws Exception {
        history.add(AssistantMessage.builder().id(UUID.randomUUID()).threadId(threadId).role("ASSISTANT")
                .content("Cuéntame tu historia.").createdAt(LocalDateTime.now().minusMinutes(1))
                .artifactsJson("[{\"type\":\"form\",\"submit\":\"Guardar\",\"fields\":[]}]").build());
        when(session.tools()).thenReturn(json.readTree(HERRAMIENTA_MARCA));
        when(llm.complete(any(), any()))
                .thenReturn(new AssistantLlmClient.Turn("", List.of(new AssistantLlmClient.ToolCall("call-1",
                        "actualizar_marca", json.readTree(argumentosDelModelo))),
                        json.createObjectNode().put("role", "assistant"), "gpt-test", 5, 2))
                .thenReturn(new AssistantLlmClient.Turn("Guardé tu historia. Sigamos con tus valores.", List.of(),
                        json.createObjectNode().put("role", "assistant"), "gpt-test", 5, 2));
        when(session.call(eq("actualizar_marca"), any())).thenReturn(json.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"));
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("Historia del negocio: ...", List.of(),
                "/panel", "fa-" + confirmado.hashCode(), null, confirmado));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
        var captor = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(session).call(eq("actualizar_marca"), captor.capture());
        return captor.getValue();
    }

    /**
     * El caso reportado: la persona escribió su historia; el modelo mandó la marca entera, con la
     * historia reescrita y "borrar" en lo que no sabía, y volvió a pedir confirmación. Se guarda
     * solo lo confirmado, con sus palabras, y se sigue de tema.
     */
    @Test
    void guardaSoloLoConfirmadoConLasPalabrasDeLaPersona() throws Exception {
        String suya = "Me motivo debido a que me encanta hacerlo desde que tenia 15 años.";
        var guardado = guardarDesdeFormulario(java.util.Map.of("historia", suya),
                "{\"historia\":\"Me motivó que me encanta hacerlo desde los 15 años.\",\"giro\":\"Pastelería\","
                        + "\"whatsapp\":\"borrar\",\"objetivo\":\"MAS_CLIENTES\"}");

        assertThat(guardado.toString()).isEqualTo(json.createObjectNode().put("historia", suya).toString());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
        assertThat(savedCalls).noneMatch(c -> c.getStatus() == AssistantToolCallStatus.WAITING_CONFIRMATION);
    }

    /** En un campo de catalogo la persona eligio la etiqueta; vale la traduccion del modelo si es valida. */
    @Test
    void enUnCampoDeCatalogoUsaLaTraduccionValidaDelModelo() throws Exception {
        var guardado = guardarDesdeFormulario(java.util.Map.of("objetivo", "Conseguir más clientes"),
                "{\"objetivo\":\"MAS_CLIENTES\",\"giro\":\"Otra cosa\"}");

        assertThat(guardado.toString()).isEqualTo("{\"objetivo\":\"MAS_CLIENTES\"}");
    }

    /**
     * Confirmar "Publicar ahora" en la tarjeta del chat no publicaba: la herramienta pide su propio
     * confirmar=true y sin él solo devolvía una vista previa. La tarjeta confirmada ya es eso.
     */
    @Test
    void alConfirmarEnElChatLaHerramientaRecibeLaConfirmacion() throws Exception {
        when(session.tools()).thenReturn(json.readTree("""
                [{"name":"publicar_ahora","title":"Publicar ahora","description":"x",
                  "inputSchema":{"type":"object","properties":{"publicacion_id":{"type":"string"},
                  "confirmar":{"type":"boolean"}}},"_meta":{"picale/policy":{"riskLevel":3,
                  "permission":"POST_PUBLISH","confirmation":"explicit","idempotencyRequired":true}}}]
                """));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-1", "publicar_ahora",
                        json.createObjectNode().put("publicacion_id", "p1"))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 1, 1))
                .thenReturn(new AssistantLlmClient.Turn("Publicada.", List.of(),
                        json.createObjectNode().put("role", "assistant"), "gpt-test", 1, 1));
        when(session.call(eq("publicar_ahora"), any())).thenReturn(json.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"));

        var result = service.send(user, threadId, new AssistantOrchestrator.Send("Publicala ya", List.of(), "/panel", "conf-1"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));
        var token = service.renewConfirmation(user, result.runId()).confirmationToken();
        service.confirm(user, result.runId(), token);

        var captor = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(session).call(eq("publicar_ahora"), captor.capture());
        assertThat(captor.getValue().path("confirmar").asBoolean()).isTrue();
        assertThat(captor.getValue().path("publicacion_id").asText()).isEqualTo("p1");
    }

    // ------------------------------------------------------------ vistas y botones de renglon

    private static final String PUB_ID = "22222222-2222-2222-2222-222222222222";

    /** Herramientas con su politica real, para que el orquestador aplique la de cada una. */
    private void catalogoDeRenglones() throws Exception {
        when(session.tools()).thenReturn(json.readTree("""
                [{"name":"publicaciones","title":"Publicaciones","description":"x","inputSchema":{"type":"object"},
                  "_meta":{"picale/policy":{"riskLevel":0,"permission":"MEMBERSHIP","confirmation":"none"}}},
                 {"name":"aprobar_propuesta","title":"Aprobar propuesta","description":"x","inputSchema":{"type":"object"},
                  "_meta":{"picale/policy":{"riskLevel":2,"permission":"POST_SCHEDULE","confirmation":"preview"}}},
                 {"name":"reintentar_publicacion","title":"Reintentar","description":"x",
                  "inputSchema":{"type":"object","properties":{"confirmar":{"type":"boolean"}}},
                  "_meta":{"picale/policy":{"riskLevel":3,"permission":"POST_PUBLISH","confirmation":"explicit"}}}]
                """));
    }

    private void tocarBoton(String herramienta, String parametro, String id, String clave) {
        var accion = json.createObjectNode().put("herramienta", herramienta);
        accion.putObject("argumentos").put(parametro, id);
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("Aprobar", List.of(), "/panel", clave,
                null, null, accion));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
    }

    /** Al consultar las publicaciones, el chat recibe su calendario con cada renglon listo para actuar. */
    @Test
    void consultarPublicacionesDejaElCalendarioEnElChat() throws Exception {
        catalogoDeRenglones();
        when(llm.complete(any(), any()))
                .thenReturn(new AssistantLlmClient.Turn("", List.of(new AssistantLlmClient.ToolCall("call-1",
                        "publicaciones", json.createObjectNode())), json.createObjectNode().put("role", "assistant"),
                        "gpt-test", 1, 1))
                .thenReturn(new AssistantLlmClient.Turn("Tienes una que fallo.", List.of(),
                        json.createObjectNode().put("role", "assistant"), "gpt-test", 1, 1));
        when(session.call(eq("publicaciones"), any())).thenReturn(json.readTree("""
                {"content":[{"type":"text","text":"1 publicacion"}],"structuredContent":{"publicaciones":[
                  {"id":"%s","estado":"FAILED","fecha":"2026-10-24T18:00","texto":"Pastel de fresa",
                   "miniatura":"https://r2.test/p.jpg","redes":[{"red":"INSTAGRAM","estado":"FAILED","error":"Token vencido"}]},
                  {"id":"no-es-un-id","estado":"DRAFT","texto":"se descarta"}]}}
                """.formatted(PUB_ID)));

        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("como van mis publicaciones",
                List.of(), "/panel", "vista-1"));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));

        var tarjetas = json.readTree(history.get(history.size() - 1).getArtifactsJson());
        var vista = tarjetas.get(0);
        assertThat(vista.path("type").asText()).isEqualTo("vista");
        assertThat(vista.path("vista").asText()).isEqualTo("calendario");
        // Solo renglones con un id real: lo que no lo es, no se puede accionar y no se muestra.
        assertThat(vista.path("items")).hasSize(1);
        assertThat(vista.at("/items/0/redes/0/error").asText()).isEqualTo("Token vencido");
    }

    /**
     * El boton «Aprobar» de su renglon no necesita que nadie interprete nada: se ejecuta sin
     * llamar al modelo, en un segundo en vez de catorce.
     */
    @Test
    void elBotonDeAprobarSeEjecutaSinPasarPorElModelo() throws Exception {
        catalogoDeRenglones();
        when(session.call(eq("aprobar_propuesta"), any()))
                .thenReturn(json.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"aprobada\"}]}"));

        tocarBoton("aprobar_propuesta", "propuesta_id", PUB_ID, "boton-1");

        verify(llm, never()).complete(any(), any());
        var captor = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(session).call(eq("aprobar_propuesta"), captor.capture());
        assertThat(captor.getValue().toString()).isEqualTo("{\"propuesta_id\":\"" + PUB_ID + "\"}");
        assertThat(history.get(history.size() - 1).getContent()).startsWith("Aprobada");
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
    }

    /** Reintentar sale publicado: aunque sea un boton, pide confirmacion antes. */
    @Test
    void elBotonDeReintentarPideConfirmacionAntesDePublicar() throws Exception {
        catalogoDeRenglones();

        tocarBoton("reintentar_publicacion", "publicacion_id", PUB_ID, "boton-2");

        verify(session, never()).call(eq("reintentar_publicacion"), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.WAITING_CONFIRMATION);
    }

    /** Un boton con una herramienta fuera de la lista no dispara nada: el mensaje sigue el camino normal. */
    @Test
    void unaAccionFueraDeLaListaSeIgnora() throws Exception {
        catalogoDeRenglones();
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("Te ayudo con eso.", List.of(),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 1, 1));

        tocarBoton("publicar_ahora", "publicacion_id", PUB_ID, "boton-3");

        verify(session, never()).call(eq("publicar_ahora"), any());
        verify(llm).complete(any(), any());
    }

    private AssistantMessage pedirConectar(String argumentos, String clave) throws Exception {
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-1", "conectar_redes", json.readTree(argumentos))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 7, 2));
        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Conecta mi Facebook", List.of(), "/panel", clave));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));
        return history.get(history.size() - 1);
    }

    /**
     * La respuesta que se pegó en el chat era "no puedo ver tu pantalla ni autorizar Facebook
     * desde aquí" y un menú adivinando la pantalla. Ahora se pone el módulo de Redes en el chat.
     */
    @Test
    void ponElModuloDeRedesEnElChatEnVezDeDecirQueNoPuede() throws Exception {
        AssistantMessage last = pedirConectar("""
                {"respuesta":"Conectemos tu Facebook para que tus publicaciones salgan ahi.",
                 "redes":["facebook","FACEBOOK","myspace","instagram"]}""", "conectar-1");

        assertThat(last.getContent()).isEqualTo("Conectemos tu Facebook para que tus publicaciones salgan ahi.");
        var card = json.readTree(last.getArtifactsJson()).get(0);
        assertThat(card.path("type").asText()).isEqualTo("social_connect");
        // Sin repetidas ni redes que el modulo no sabe conectar.
        assertThat(card.path("networks").toString()).isEqualTo("[\"facebook\",\"instagram\"]");
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
        // El enlace lo pide la web con su sesion: el chat no toca el MCP ni deja recibo.
        verify(session, never()).call(any(), any());
        assertThat(savedCalls).isEmpty();
    }

    /** Sin permiso para administrar redes, el boton fallaria: se dice a quien pedirselo. */
    @Test
    void sinPermisoDeRedesDiceAQuienPedirseloSinBoton() throws Exception {
        when(permissions.permisosDe(user)).thenReturn(EnumSet.of(Permission.POST_CREATE));

        AssistantMessage last = pedirConectar("""
                {"respuesta":"Conectemos tu Facebook.","redes":["facebook"]}""", "conectar-2");

        assertThat(last.getContent()).contains("quien administra este espacio");
        assertThat(last.getArtifactsJson() == null || !last.getArtifactsJson().contains("social_connect")).isTrue();
    }

    @Test
    void enseñaLaRespuestaQueViajaConLasOpciones() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-1", "ofrecer_opciones", json.readTree("""
                        {"respuesta":"Tono: cercano y confiable.","pregunta":"¿La guardo asi?",
                         "opciones":[{"etiqueta":"Si"},{"etiqueta":"Cambiar"}]}"""))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 7, 2));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Completa mi marca", List.of(), "/panel", "message-12"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        AssistantMessage last = history.get(history.size() - 1);
        assertThat(last.getContent()).contains("Tono: cercano y confiable.").endsWith("¿La guardo asi?");
    }

    @Test
    void conservaLaPropuestaQueAcompanaALaPregunta() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn(
                "Propongo esto para tu marca:\n- Tono: cercano", List.of(
                new AssistantLlmClient.ToolCall("call-1", "ofrecer_opciones", json.readTree("""
                        {"pregunta":"¿La guardo asi?","opciones":[{"etiqueta":"Si"},{"etiqueta":"Cambiar"}]}"""))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 7, 2));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Completa mi marca", List.of(), "/panel", "message-11"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        // Sin esto la persona elegiria sobre una propuesta que no puede ver.
        AssistantMessage last = history.get(history.size() - 1);
        assertThat(last.getContent()).contains("Tono: cercano").endsWith("¿La guardo asi?");
    }

    @Test
    void ofreceLaHerramientaDeOpcionesJuntoAlCatalogo() throws Exception {
        when(session.tools()).thenReturn(json.readTree("""
                [{"name":"panel","description":"Consulta el panel","inputSchema":{"type":"object"},
                  "_meta":{"picale/policy":{"riskLevel":0,"permission":"MEMBERSHIP",
                  "confirmation":"none","idempotencyRequired":false}}}]
                """));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("Listo.", List.of(),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 5, 1));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Hola", List.of(), "/panel", "message-9"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        var ofrecidas = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(llm).complete(any(), ofrecidas.capture());
        assertThat(ofrecidas.getValue().toString()).contains("panel").contains("ofrecer_opciones")
                .contains("pedir_datos").contains("responder_con_bloques");
    }

    @Test
    void respondePorBloquesYResuelveElArchivoSinExponerSuId() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(media.findById(assetId)).thenReturn(Optional.of(MediaAsset.builder()
                .id(assetId).tenantId(workspaceId.toString()).status(MediaAssetStatus.READY)
                .type(MediaType.IMAGE).fileName("tamalitos.jpg").url("https://cdn.test/tamalitos.jpg").build()));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-blocks", "responder_con_bloques", json.readTree("""
                        {"bloques":[
                          {"tipo":"texto","markdown":"Esta foto sí sirve para una publicación."},
                          {"tipo":"media","archivo":"%s","pie":"La foto que propongo"},
                          {"tipo":"propuesta","titulo":"Publicación lista","campos":[
                            {"etiqueta":"Formato","valor":"Foto"},
                            {"etiqueta":"Redes","valor":"Instagram y Facebook"}]},
                          {"tipo":"opciones","pregunta":"¿Creo el borrador?","opciones":["Sí, créalo","Quiero cambiarlo"]}
                        ]}
                        """.formatted(assetId)))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 15, 5));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Usa esta foto", List.of(), "/panel/contenido", "message-blocks"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        AssistantMessage answer = history.get(history.size() - 1);
        assertThat(answer.getArtifactsJson()).contains("\"type\":\"texto\"")
                .contains("\"type\":\"media\"").contains("https://cdn.test/tamalitos.jpg")
                .contains("\"type\":\"propuesta\"").contains("\"type\":\"choices\"")
                .contains("\"id\":\"" + assetId + "\""); // Metadato para seleccionar, no texto visible.
        assertThat(answer.getContent()).contains("Esta foto sí sirve").contains("Publicación lista")
                .contains("¿Creo el borrador?").doesNotContain(assetId.toString());
        verify(session, never()).call(any(), any());
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
    }

    @Test
    void omiteUnArchivoDeOtroWorkspaceSinPerderElRestoDeLaRespuesta() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(media.findById(assetId)).thenReturn(Optional.of(MediaAsset.builder()
                .id(assetId).tenantId(UUID.randomUUID().toString()).status(MediaAssetStatus.READY)
                .type(MediaType.IMAGE).url("https://cdn.test/ajena.jpg").build()));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-blocks", "responder_con_bloques", json.readTree("""
                        {"bloques":[
                          {"tipo":"texto","markdown":"Puedo ayudarte con una propuesta segura."},
                          {"tipo":"media","archivo":"%s","pie":"No debe aparecer"}
                        ]}
                        """.formatted(assetId)))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 10, 3));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Ayúdame", List.of(), "/panel", "message-foreign-media"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        AssistantMessage answer = history.get(history.size() - 1);
        assertThat(answer.getArtifactsJson()).contains("Puedo ayudarte").doesNotContain("ajena.jpg")
                .doesNotContain(assetId.toString());
        assertThat(answer.getContent()).isEqualTo("Puedo ayudarte con una propuesta segura.");
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
    }

    @Test
    void omiteUnArchivoInexistenteSinPerderElRestoDeLaRespuesta() throws Exception {
        UUID assetId = UUID.randomUUID();
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(media.findById(assetId)).thenReturn(Optional.empty());
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-missing-media", "responder_con_bloques", json.readTree("""
                        {"bloques":[
                          {"tipo":"media","archivo":"%s","pie":"No debe aparecer"},
                          {"tipo":"texto","markdown":"La respuesta sigue disponible."}
                        ]}
                        """.formatted(assetId)))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 10, 3));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Ayúdame", List.of(), "/panel", "message-missing-media"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        AssistantMessage answer = history.get(history.size() - 1);
        assertThat(answer.getArtifactsJson()).contains("La respuesta sigue disponible")
                .doesNotContain("No debe aparecer").doesNotContain(assetId.toString());
        assertThat(answer.getContent()).isEqualTo("La respuesta sigue disponible.");
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
    }

    @Test
    void limitaBloquesCamposDePropuestaYOpcionesAunqueElModeloSeExceda() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-limits", "responder_con_bloques", json.readTree("""
                        {"bloques":[
                          {"tipo":"propuesta","titulo":"Propuesta","campos":[
                            {"etiqueta":"Campo 1","valor":"1"},{"etiqueta":"Campo 2","valor":"2"},
                            {"etiqueta":"Campo 3","valor":"3"},{"etiqueta":"Campo 4","valor":"4"},
                            {"etiqueta":"Campo 5","valor":"5"},{"etiqueta":"Campo 6","valor":"6"},
                            {"etiqueta":"Campo 7","valor":"7"},{"etiqueta":"Campo 8","valor":"8"},
                            {"etiqueta":"Campo 9","valor":"9"},{"etiqueta":"Campo 10","valor":"10"},
                            {"etiqueta":"Campo 11","valor":"11"}]},
                          {"tipo":"opciones","pregunta":"Elige","opciones":["1","2","3","4","5","6"]},
                          {"tipo":"texto","markdown":"Bloque 3"},{"tipo":"texto","markdown":"Bloque 4"},
                          {"tipo":"texto","markdown":"Bloque 5"},{"tipo":"texto","markdown":"Bloque 6"},
                          {"tipo":"texto","markdown":"Bloque 7"},{"tipo":"texto","markdown":"Bloque 8"},
                          {"tipo":"texto","markdown":"Bloque 9"}
                        ]}
                        """))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 10, 3));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Prueba límites", List.of(), "/panel", "message-block-limits"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        var artifacts = json.readTree(history.get(history.size() - 1).getArtifactsJson());
        assertThat(artifacts).hasSize(8);
        assertThat(artifacts.get(0).path("fields")).hasSize(10);
        assertThat(artifacts.get(1).path("options")).hasSize(5);
        assertThat(history.get(history.size() - 1).getContent()).doesNotContain("Campo 11").doesNotContain("Bloque 9");
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
    }

    @Test
    void ignoraLasOpcionesSiNoHayDondeElegir() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-1", "ofrecer_opciones", json.readTree("""
                        {"pregunta":"¿Seguimos?","opciones":[{"etiqueta":"Si"}]}"""))),
                json.createObjectNode().put("role", "assistant"), "gpt-test", 7, 2));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Ayudame", List.of(), "/panel", "message-10"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        AssistantMessage last = history.get(history.size() - 1);
        assertThat(last.getContent()).isEqualTo("¿Seguimos?");
        assertThat(last.getArtifactsJson()).isNull();
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
    }

    @Test
    void expandeElAtajoDelCatalogoSinEnsenarleLasInstruccionesALaPersona() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(session.resource("picale://reglas")).thenReturn("Instagram: 2200 caracteres.");
        when(session.prompt(eq("arranque_del_dia"), any()))
                .thenReturn("Prepara el arranque del dia. Pasos: 1. `panel` con dias=2.");
        when(llm.complete(any(), any()))
                .thenReturn(new AssistantLlmClient.Turn("Esto es lo de hoy.", List.of(),
                        json.createObjectNode().put("role", "assistant"), "gpt-test", 9, 3));

        AssistantOrchestrator.Result result = service.send(user, threadId, new AssistantOrchestrator.Send(
                "Arranque del día", List.of(), "/panel", "message-5", "arranque_del_dia"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        var enviado = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
        verify(llm).complete(enviado.capture(), any());
        String system = enviado.getValue().get(0).path("content").asText();
        assertThat(system).contains("2200 caracteres").contains("`panel` con dias=2");
        // Lo que ve la persona es el titulo del atajo, no los pasos internos.
        assertThat(history.get(0).getContent()).isEqualTo("Arranque del día");
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
    }

    @Test
    void sigueRespondiendoSiElCatalogoNoDaReglasNiAtajo() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(session.resource(any())).thenThrow(new AssistantMcpClient.McpException(503, "sin catalogo"));
        when(session.prompt(any(), any())).thenThrow(new AssistantMcpClient.McpException(503, "sin catalogo"));
        when(llm.complete(any(), any()))
                .thenReturn(new AssistantLlmClient.Turn("Listo.", List.of(),
                        json.createObjectNode().put("role", "assistant"), "gpt-test", 4, 1));

        AssistantOrchestrator.Result result = service.send(user, threadId, new AssistantOrchestrator.Send(
                "Arranque del día", List.of(), "/panel", "message-6", "arranque_del_dia"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
    }

    @Test
    void rechazaUnAtajoConNombreInvalido() {
        assertThatThrownBy(() -> service.send(user, threadId, new AssistantOrchestrator.Send(
                "hola", List.of(), "/panel", "message-7", "../otro")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("atajo");
    }

    @Test
    void cierraConRespuestaCuandoElModeloNoDejaDeConsultar() throws Exception {
        when(session.tools()).thenReturn(json.readTree("""
                [{"name":"panel","description":"Consulta el panel","inputSchema":{"type":"object"},
                  "_meta":{"picale/policy":{"riskLevel":0,"permission":"MEMBERSHIP",
                  "confirmation":"none","idempotencyRequired":false}}}]
                """));
        when(session.call(eq("panel"), any()))
                .thenReturn(json.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"sin datos\"}]}"));
        // Con herramientas el modelo sigue consultando; en el ultimo paso se le retiran y responde.
        when(llm.complete(any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new AssistantLlmClient.Turn("Esto es lo que alcance a revisar.", List.of(),
                        json.createObjectNode().put("role", "assistant"), "gpt-test", 5, 1));
        when(llm.complete(any(), org.mockito.ArgumentMatchers.notNull()))
                .thenReturn(new AssistantLlmClient.Turn("", List.of(
                        new AssistantLlmClient.ToolCall("call-1", "panel", json.createObjectNode())),
                        json.createObjectNode().put("role", "assistant"), "gpt-test", 10, 2));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Como vamos?", List.of(), "/panel", "message-3"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.COMPLETED);
        assertThat(history.get(history.size() - 1).getContent()).isEqualTo("Esto es lo que alcance a revisar.");
    }

    @Test
    void explicaLaCaidaDelProveedorSinCulparALaPersona() throws Exception {
        when(session.tools()).thenReturn(json.readTree("[]"));
        when(llm.complete(any(), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connect timed out"));

        AssistantOrchestrator.Result result = service.send(user, threadId,
                new AssistantOrchestrator.Send("Ayudame", List.of(), "/panel", "message-4"));
        service.process(new AssistantRunDispatcher.Requested(result.runId(), userId, workspaceId));

        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.FAILED);
        assertThat(history.get(history.size() - 1).getContent())
                .contains("No pude completar la conexión").contains("revisa su estado")
                .doesNotContain("No cambie nada");
    }

    @Test
    void conservaGaleriaVerificadaOrdenadaYSinArchivosDeOtroWorkspace() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), foreign = UUID.randomUUID();
        for (UUID id : List.of(first, second, foreign)) {
            when(media.findById(id)).thenReturn(Optional.of(MediaAsset.builder().id(id)
                    .tenantId(id.equals(foreign) ? UUID.randomUUID().toString() : workspaceId.toString())
                    .type(MediaType.IMAGE).status(MediaAssetStatus.READY).fileName("Imagen")
                    .url("https://media.test/" + id + ".png").build()));
        }
        ObjectNode receipt = json.createObjectNode();
        var data = receipt.putObject("structuredContent").put("estado", "READY");
        var version = data.putArray("versiones").addObject().put("proporcion", "1:1").put("estado", "READY");
        version.putArray("archivo_ids").add(first.toString()).add(second.toString()).add(foreign.toString());
        version.putArray("imagenes").add("a").add("b").add("c");
        consultarTrabajo(receipt, true);

        var message = history.stream().filter(m -> m.getArtifactsJson() != null
                && m.getArtifactsJson().contains("\"generation\"")).findFirst().orElseThrow();
        var card = json.readTree(message.getArtifactsJson()).get(0);
        assertThat(card.path("status").asText()).isEqualTo("READY");
        var gallery = card.path("versions").get(0);
        assertThat(gallery.path("items").size()).isEqualTo(2);
        assertThat(gallery.path("items").get(0).path("id").asText()).isEqualTo(first.toString());
        assertThat(gallery.path("items").get(1).path("id").asText()).isEqualTo(second.toString());
        assertThat(gallery.path("complete").asBoolean()).isFalse();
        assertThat(message.getArtifactsJson()).doesNotContain(foreign.toString());
        // El recibo sobrevive aunque falle la redacción posterior del modelo.
        assertThat(savedRun.getStatus()).isEqualTo(AssistantRunStatus.FAILED);
    }

    @Test
    void guardaElTrabajoPendienteParaRetomarloSinGenerarOtraVez() throws Exception {
        ObjectNode receipt = json.createObjectNode();
        receipt.putObject("structuredContent").put("estado", "CREATING");
        consultarTrabajo(receipt, false);
        assertThat(history).anySatisfy(m -> assertThat(m.getArtifactsJson()).contains("\"status\":\"CREATING\""));
        verify(session, never()).call(eq("generar_contenido"), any());
    }

    @Test
    void actualizaUnaSolaTarjetaAlConsultarElMismoTrabajo() throws Exception {
        ObjectNode receipt = json.createObjectNode();
        receipt.putObject("structuredContent").put("estado", "CREATING");
        consultarTrabajo(receipt, false);
        // Repite la llamada con el mismo ID del trabajo, como al retomar desde su tarjeta.
        var call = savedCalls.get(0);
        receipt.withObject("structuredContent").put("estado", "READY");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "saveGenerationReceipt",
                savedRun, call, receipt, false);
        var receipts = history.stream().filter(m -> m.getArtifactsJson() != null
                && m.getArtifactsJson().contains("\"generation\"")).toList();
        assertThat(receipts).hasSize(1);
        assertThat(receipts.get(0).getArtifactsJson()).contains("\"status\":\"READY\"");
    }

    @Test
    void rechazaUnaSeleccionVisualDeOtroWorkspaceAntesDeCrearElTurno() {
        UUID assetId = UUID.randomUUID();
        when(media.findById(assetId)).thenReturn(Optional.of(MediaAsset.builder().id(assetId)
                .tenantId(UUID.randomUUID().toString()).type(MediaType.IMAGE).status(MediaAssetStatus.READY).build()));
        assertThatThrownBy(() -> service.send(user, threadId, new AssistantOrchestrator.Send(
                "Usa esta imagen", List.of(assetId), "/panel/asistente", "foreign-image")))
                .isInstanceOf(com.metricol.api.exception.ResourceNotFoundException.class);
        verify(runs, never()).save(any());
    }

    @Test
    void noPresentaUnErrorMcpComoAccionCompletada() throws Exception {
        ObjectNode receipt = json.createObjectNode().put("isError", true);
        receipt.putObject("structuredContent").put("estado", "FAILED");
        receipt.putArray("content").addObject().put("type", "text").put("text", "No se pudo generar.");
        consultarTrabajo(receipt, false);
        assertThat(savedCalls).singleElement().satisfies(call -> assertThat(call.getStatus()).isEqualTo(AssistantToolCallStatus.FAILED));
        assertThat(thread.getSummary()).contains("Intento que no se completo");
        assertThat(history).anySatisfy(m -> assertThat(m.getArtifactsJson()).contains("\"status\":\"FAILED\""));
    }

    private void consultarTrabajo(ObjectNode receipt, boolean failAfterTool) throws Exception {
        when(session.tools()).thenReturn(json.readTree("""
                [{"name":"estado_generacion","description":"Consulta generación","inputSchema":{"type":"object"},
                  "_meta":{"picale/policy":{"riskLevel":0,"permission":"MEMBERSHIP","confirmation":"none"}}}]
                """));
        var args = json.createObjectNode().put("trabajo_id", UUID.randomUUID().toString());
        var stubbing = when(llm.complete(any(), any())).thenReturn(new AssistantLlmClient.Turn("", List.of(
                new AssistantLlmClient.ToolCall("call-generation", "estado_generacion", args)),
                json.createObjectNode().put("role", "assistant"), "test", 1, 1));
        if (failAfterTool) stubbing.thenThrow(new IllegalStateException("redacción interrumpida"));
        else stubbing.thenReturn(new AssistantLlmClient.Turn("Revisé el trabajo.", List.of(),
                json.createObjectNode().put("role", "assistant"), "test", 1, 1));
        when(session.call(eq("estado_generacion"), any())).thenReturn(receipt);
        var sent = service.send(user, threadId, new AssistantOrchestrator.Send("Revisa la generación", List.of(), "/panel/asistente", "generation-test"));
        service.process(new AssistantRunDispatcher.Requested(sent.runId(), userId, workspaceId));
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
