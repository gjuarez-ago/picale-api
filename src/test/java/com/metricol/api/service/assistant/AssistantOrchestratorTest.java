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
        assertThat(ofrecidas.getValue().toString()).contains("panel").contains("ofrecer_opciones");
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
                .contains("no pude conectarme").contains("No cambie nada");
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
