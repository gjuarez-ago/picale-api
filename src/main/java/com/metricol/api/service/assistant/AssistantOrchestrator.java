package com.metricol.api.service.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metricol.api.entity.AssistantMessage;
import com.metricol.api.entity.AssistantRun;
import com.metricol.api.entity.AssistantThread;
import com.metricol.api.entity.AssistantToolCall;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.User;
import com.metricol.api.enums.AssistantRunStatus;
import com.metricol.api.enums.AssistantToolCallStatus;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.Permission;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.AssistantMessageRepository;
import com.metricol.api.repository.AssistantRunRepository;
import com.metricol.api.repository.AssistantThreadRepository;
import com.metricol.api.repository.AssistantToolCallRepository;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.service.PermissionService;

/** Ejecuta un turno del asistente usando al MCP como unica superficie operativa. */
@Service
public class AssistantOrchestrator {

    public record Send(String content, List<UUID> attachmentIds, String currentPath, String idempotencyKey,
            String promptName) {

        public Send(String content, List<UUID> attachmentIds, String currentPath, String idempotencyKey) {
            this(content, attachmentIds, currentPath, idempotencyKey, null);
        }
    }

    /** Un flujo experto del catalogo MCP, tal como se le ofrece a la persona. */
    public record Starter(String name, String title, String description) {
    }

    public record Result(AssistantThreads.Detail detail, UUID runId, String confirmationToken) {
    }

    private static final Logger log = LoggerFactory.getLogger(AssistantOrchestrator.class);
    // Seis pasos alcanzaban para responder una pregunta, pero no para acompanar un alta:
    // leer la marca, la cuenta y el contenido, guardar y verificar ya los agotaba.
    private static final int MAX_TOOL_STEPS = 12;
    private static final String REGLAS_URI = "picale://reglas";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SYSTEM_PROMPT = """
            Eres el Asistente Operativo de Picale. Ayudas a entender la cuenta, decidir y ejecutar trabajo real.
            Usa herramientas para cualquier afirmacion sobre datos actuales; nunca inventes resultados ni digas que
            hiciste algo sin el recibo de la herramienta. Distingue con claridad hechos, inferencias y recomendaciones.
            No menciones nombres internos de herramientas. Se breve, concreto y profesional. Si hay archivos adjuntos,
            sus IDs aparecen en el mensaje y debes inspeccionarlos con la herramienta disponible antes de opinar.
            Las acciones sensibles se detendran para confirmacion: describe exactamente que se propone y su efecto.
            No solicites claves, tokens ni secretos. No intentes acciones fuera del catalogo de herramientas.

            Cuentas nuevas o incompletas: tu primer trabajo es dejar la cuenta lista para operar, sin mandar a la
            persona a configurarla por su cuenta. Si falta informacion de la marca, si no hay redes conectadas o si
            no hay material, acompanala paso a paso: revisa que falta con las herramientas, pide un solo dato a la
            vez en palabras simples y con un ejemplo, y guarda lo que te vaya dando con la herramienta
            correspondiente. No pidas de golpe una lista larga de campos ni le devuelvas un instructivo numerado
            para que lo haga sola. Cuando un dato quede guardado, dilo y continua con el siguiente que falte.
            Lo que no puedas hacer tu (conectar una red social, por ejemplo) explicalo en una frase, di donde se
            hace, y sigue avanzando con lo que si este a tu alcance en vez de detenerte ahi.
            """;

    private final AssistantThreads threadService;
    private final AssistantThreadRepository threads;
    private final AssistantMessageRepository messages;
    private final AssistantRunRepository runs;
    private final AssistantToolCallRepository calls;
    private final MediaAssetRepository media;
    private final PermissionService permissions;
    private final AssistantMcpClient mcp;
    private final AssistantLlmClient llm;
    private final AssistantRunEvents events;
    private final UserRepository users;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper json;

    public AssistantOrchestrator(AssistantThreads threadService, AssistantThreadRepository threads,
            AssistantMessageRepository messages, AssistantRunRepository runs,
            AssistantToolCallRepository calls, MediaAssetRepository media, PermissionService permissions,
            AssistantMcpClient mcp, AssistantLlmClient llm, AssistantRunEvents events, UserRepository users,
            ApplicationEventPublisher publisher, ObjectMapper json) {
        this.threadService = threadService;
        this.threads = threads;
        this.messages = messages;
        this.runs = runs;
        this.calls = calls;
        this.media = media;
        this.permissions = permissions;
        this.mcp = mcp;
        this.llm = llm;
        this.events = events;
        this.users = users;
        this.publisher = publisher;
        this.json = json;
    }

    @Transactional
    public Result send(User user, UUID threadId, Send request) {
        AssistantThreads.Detail detail = threadService.detail(user, threadId);
        AssistantThread thread = detail.thread();
        if (thread.getClosedAt() != null) {
            throw new IllegalArgumentException("Reabre la conversacion antes de enviar otro mensaje.");
        }
        String content = cleanContent(request == null ? null : request.content());
        String key = cleanKey(request == null ? null : request.idempotencyKey());
        var prior = runs.findByWorkspaceIdAndIdempotencyKey(thread.getWorkspaceId(), key);
        if (prior.isPresent()) {
            if (!threadId.equals(prior.get().getThreadId())) {
                throw new IllegalArgumentException("Esa clave de idempotencia ya se uso en otra conversacion.");
            }
            return new Result(threadService.detail(user, threadId), prior.get().getId(), null);
        }

        String attachments = validateAttachments(request == null ? List.of() : request.attachmentIds());
        LocalDateTime now = LocalDateTime.now();
        AssistantRun run = runs.save(AssistantRun.builder()
                .workspaceId(thread.getWorkspaceId()).threadId(threadId).userId(user.getId())
                .status(AssistantRunStatus.QUEUED).idempotencyKey(key)
                .contextPath(cleanPath(request == null ? null : request.currentPath()))
                .promptName(cleanPromptName(request == null ? null : request.promptName()))
                .createdAt(now).build());
        messages.save(AssistantMessage.builder().threadId(threadId).runId(run.getId()).role("USER")
                .content(content).attachmentsJson(attachments).createdAt(now).build());
        updateThread(thread, content, now);
        events.emit(run, "run.queued", payload("runId", run.getId().toString()));
        publisher.publishEvent(new AssistantRunDispatcher.Requested(run.getId(), user.getId(), thread.getWorkspaceId()));
        return new Result(threadService.detail(user, threadId), run.getId(), null);
    }

    /** Trabajo pesado invocado por el dispatcher, ya dentro del tenant correcto. */
    @Transactional
    public void process(AssistantRunDispatcher.Requested requested) {
        AssistantRun run = runs.findLockedById(requested.runId()).orElse(null);
        if (run == null || run.getStatus() != AssistantRunStatus.QUEUED
                || !requested.workspaceId().equals(run.getWorkspaceId())
                || !requested.userId().equals(run.getUserId())) return;
        run.setStatus(AssistantRunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        runs.save(run);
        events.emit(run, "run.started", payload("runId", run.getId().toString()));
        try {
            User user = users.findById(requested.userId())
                    .filter(item -> item.getWorkspace() != null
                            && requested.workspaceId().equals(item.getWorkspace().getId()))
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "La persona que inicio el turno ya no esta disponible."));
            AssistantThread thread = threadService.detail(user, run.getThreadId()).thread();
            Set<Permission> allowed = permissions.permisosDe(user);
            AssistantMcpClient.Session session = mcp.open(user, run.getThreadId(), run.getId(), allowed);
            JsonNode tools = session.tools();
            ArrayNode history = history(run.getThreadId(), run.getContextPath(), briefing(session, run));
            execute(user, thread, run, history, tools, session, 0);
        } catch (Exception error) {
            fail(run, safeError(error), error);
        }
    }

    @Transactional
    public void dispatchFailed(AssistantRunDispatcher.Requested requested, String message) {
        runs.findLockedById(requested.runId())
                .filter(run -> run.getStatus() == AssistantRunStatus.QUEUED
                        && requested.workspaceId().equals(run.getWorkspaceId())
                        && requested.userId().equals(run.getUserId()))
                .ifPresent(run -> fail(run, message));
    }

    @Transactional
    public Result confirm(User user, UUID runId, String token) {
        AssistantRun run = ownedRunLocked(user, runId);
        if (run.getStatus() != AssistantRunStatus.WAITING_CONFIRMATION) {
            throw new IllegalArgumentException("Esta accion ya no espera confirmacion.");
        }
        AssistantToolCall call = calls.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(item -> item.getStatus() == AssistantToolCallStatus.WAITING_CONFIRMATION)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No hay una accion pendiente."));
        if (token == null || call.getConfirmationTokenHash() == null
                || call.getConfirmationExpiresAt() == null
                || call.getConfirmationExpiresAt().isBefore(LocalDateTime.now())
                || !constantEquals(call.getConfirmationTokenHash(), hash(token))) {
            throw new IllegalArgumentException("La confirmacion no es valida o ya vencio.");
        }

        call.setConfirmationTokenHash(null);
        call.setConfirmationExpiresAt(null);
        call.setConfirmedBy(user.getId());
        call.setConfirmedAt(LocalDateTime.now());
        call.setStatus(AssistantToolCallStatus.RUNNING);
        calls.save(call);
        run.setStatus(AssistantRunStatus.RUNNING);
        runs.save(run);

        try {
            AssistantMcpClient.Session session = mcp.open(user, run.getThreadId(), runId, permissions.permisosDe(user));
            JsonNode tools = session.tools();
            JsonNode arguments = json.readTree(call.getArgumentsJson());
            long started = System.nanoTime();
            JsonNode result = session.call(call.getToolName(), arguments);
            completeCall(run, call, result, started);
            ArrayNode history = history(run.getThreadId(), null);
            history.add(assistantToolRequest(call));
            history.add(toolResult(call, result));
            AssistantThread thread = threadService.detail(user, run.getThreadId()).thread();
            return execute(user, thread, run, history, tools, session, 1);
        } catch (Exception error) {
            call.setStatus(AssistantToolCallStatus.FAILED);
            call.setResultSummary(safeError(error));
            calls.save(call);
            fail(run, safeError(error), error);
            return new Result(threadService.detail(user, run.getThreadId()), runId, null);
        }
    }

    @Transactional
    public Result renewConfirmation(User user, UUID runId) {
        AssistantRun run = ownedRunLocked(user, runId);
        if (run.getStatus() != AssistantRunStatus.WAITING_CONFIRMATION) {
            throw new IllegalArgumentException("Esta accion ya no espera confirmacion.");
        }
        AssistantToolCall call = calls.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(item -> item.getStatus() == AssistantToolCallStatus.WAITING_CONFIRMATION)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No hay una accion pendiente."));
        String rawToken = token();
        call.setConfirmationTokenHash(hash(rawToken));
        call.setConfirmationExpiresAt(LocalDateTime.now().plus(15, ChronoUnit.MINUTES));
        calls.save(call);
        return new Result(threadService.detail(user, run.getThreadId()), runId, rawToken);
    }

    /**
     * Los atajos que ve la persona salen del catalogo MCP, no de una lista escrita en la web:
     * asi un flujo nuevo en el servidor aparece solo, sin tocar ni desplegar el front.
     */
    @Transactional(readOnly = true)
    public List<Starter> starters(User user) {
        try {
            AssistantMcpClient.Session session = mcp.open(user, null, null, permissions.permisosDe(user));
            List<Starter> result = new java.util.ArrayList<>();
            for (JsonNode prompt : session.prompts()) {
                String name = prompt.path("name").asText("");
                if (name.isBlank()) continue;
                result.add(new Starter(name, prompt.path("title").asText(name),
                        prompt.path("description").asText("")));
            }
            return List.copyOf(result);
        } catch (RuntimeException e) {
            // Sin atajos el chat sigue siendo utilizable: se escribe a mano.
            log.warn("No se pudo leer el catalogo de atajos del asistente", e);
            return List.of();
        }
    }

    @Transactional
    public AssistantThreads.Detail cancel(User user, UUID runId) {
        AssistantRun run = ownedRunLocked(user, runId);
        if (run.getStatus() == AssistantRunStatus.WAITING_CONFIRMATION) {
            calls.findByRunIdOrderByCreatedAtAsc(runId).stream()
                    .filter(call -> call.getStatus() == AssistantToolCallStatus.WAITING_CONFIRMATION)
                    .forEach(call -> {
                        call.setStatus(AssistantToolCallStatus.CANCELLED);
                        call.setConfirmationTokenHash(null);
                        call.setConfirmationExpiresAt(null);
                        calls.save(call);
                    });
            run.setStatus(AssistantRunStatus.CANCELLED);
            run.setFinishedAt(LocalDateTime.now());
            runs.save(run);
            messages.save(AssistantMessage.builder().threadId(run.getThreadId()).runId(runId).role("ASSISTANT")
                    .content("Accion cancelada. No hice ningun cambio.").createdAt(LocalDateTime.now()).build());
            events.emit(run, "run.cancelled", payload("runId", runId.toString()));
        }
        return threadService.detail(user, run.getThreadId());
    }

    private Result execute(User user, AssistantThread thread, AssistantRun run, ArrayNode history,
            JsonNode tools, AssistantMcpClient.Session session, int completedSteps) {
        for (int step = completedSteps; step < MAX_TOOL_STEPS; step++) {
            // En el ultimo paso se retiran las herramientas: el modelo ya no puede seguir
            // explorando y tiene que responder con lo que reunio. Antes se quedaba sin pasos
            // y el turno moria con un error, dejando a la persona sin ninguna respuesta.
            boolean lastStep = step == MAX_TOOL_STEPS - 1;
            AssistantLlmClient.Turn turn = llm.complete(history, lastStep ? null : tools);
            run.setModel(turn.model());
            runs.save(run);
            if (turn.toolCalls().isEmpty()) {
                String answer = turn.content().isBlank()
                        ? "Termine la revision, pero no pude redactar una respuesta util. Intenta formularlo de otra forma."
                        : turn.content();
                messages.save(AssistantMessage.builder().threadId(thread.getId()).runId(run.getId())
                        .role("ASSISTANT").content(answer).model(turn.model())
                        .artifactsJson(resultArtifacts(run.getId(), tools))
                        .inputTokens(turn.inputTokens()).outputTokens(turn.outputTokens())
                        .createdAt(LocalDateTime.now()).build());
                events.emit(run, "message.delta", payload("content", answer));
                run.setStatus(AssistantRunStatus.COMPLETED);
                run.setFinishedAt(LocalDateTime.now());
                runs.save(run);
                thread.setUpdatedAt(LocalDateTime.now());
                threads.save(thread);
                events.emit(run, "run.completed", payload("message", answer));
                return new Result(threadService.detail(user, thread.getId()), run.getId(), null);
            }

            history.add(turn.assistantMessage());
            for (AssistantLlmClient.ToolCall requested : turn.toolCalls()) {
                JsonNode definition = findTool(tools, requested.name());
                Policy policy = policy(definition);
                AssistantToolCall call = calls.save(AssistantToolCall.builder().runId(run.getId())
                        .toolName(requested.name()).riskLevel(policy.riskLevel()).permission(policy.permission())
                        .argumentsJson(sanitize(requested.arguments()).toString())
                        .status(AssistantToolCallStatus.PROPOSED).createdAt(LocalDateTime.now()).build());
                requirePermission(user, policy.permission());
                if (policy.requiresConfirmation()) {
                    String rawToken = token();
                    call.setStatus(AssistantToolCallStatus.WAITING_CONFIRMATION);
                    call.setConfirmationTokenHash(hash(rawToken));
                    call.setConfirmationExpiresAt(LocalDateTime.now().plus(15, ChronoUnit.MINUTES));
                    calls.save(call);
                    run.setStatus(AssistantRunStatus.WAITING_CONFIRMATION);
                    runs.save(run);
                    messages.save(confirmationMessage(thread.getId(), run.getId(), call, definition, rawToken,
                            policy.confirmation()));
                    events.emit(run, "confirmation.required", payload("toolCallId", call.getId().toString()));
                    return new Result(threadService.detail(user, thread.getId()), run.getId(), rawToken);
                }

                call.setStatus(AssistantToolCallStatus.RUNNING);
                calls.save(call);
                events.emit(run, "tool.started", payload("toolCallId", call.getId().toString()));
                long started = System.nanoTime();
                JsonNode result = session.call(requested.name(), requested.arguments());
                completeCall(run, call, result, started);
                history.add(toolResult(requested.id(), result));
            }
        }
        throw new IllegalStateException("Se alcanzo el limite seguro de pasos del asistente.");
    }

    private ArrayNode history(UUID threadId, String currentPath) {
        return history(threadId, currentPath, "");
    }

    /**
     * Las reglas de publicacion y los flujos expertos los mantiene el catalogo MCP. Traerlos
     * aqui evita que la web y el modelo inventen su propia version de las mismas reglas.
     */
    private String briefing(AssistantMcpClient.Session session, AssistantRun run) {
        StringBuilder extra = new StringBuilder();
        try {
            String reglas = session.resource(REGLAS_URI);
            if (!reglas.isBlank()) {
                extra.append("\n\nReglas de publicacion vigentes (no inventes otras):\n").append(reglas);
            }
        } catch (RuntimeException e) {
            log.warn("No se pudieron leer las reglas del catalogo MCP para el turno {}", run.getId(), e);
        }
        if (run.getPromptName() != null && !run.getPromptName().isBlank()) {
            try {
                String flujo = session.prompt(run.getPromptName(), null);
                if (!flujo.isBlank()) {
                    extra.append("\n\nLa persona pidio este trabajo desde un atajo. Sigue estas instrucciones")
                            .append(" sin recitarlas ni mencionar nombres internos:\n").append(flujo);
                }
            } catch (RuntimeException e) {
                log.warn("No se pudo expandir el flujo '{}' del turno {}", run.getPromptName(), run.getId(), e);
            }
        }
        return extra.toString();
    }

    private ArrayNode history(UUID threadId, String currentPath, String briefing) {
        ArrayNode result = json.createArrayNode();
        result.addObject().put("role", "system").put("content", SYSTEM_PROMPT
                + ((currentPath == null || currentPath.isBlank()) ? "" : "\nLa persona esta en: " + currentPath)
                + (briefing == null ? "" : briefing));
        List<AssistantMessage> all = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
        int start = Math.max(0, all.size() - 20);
        for (AssistantMessage message : all.subList(start, all.size())) {
            if ("SYSTEM".equals(message.getRole())) continue;
            String content = message.getContent();
            if (message.getAttachmentsJson() != null && !message.getAttachmentsJson().isBlank()) {
                content += "\nArchivos adjuntos (usa estos IDs): " + message.getAttachmentsJson();
            }
            result.addObject().put("role", message.getRole().toLowerCase(Locale.ROOT)).put("content", content);
        }
        return result;
    }

    private String validateAttachments(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return null;
        if (ids.size() > 10) throw new IllegalArgumentException("Puedes adjuntar hasta 10 archivos por mensaje.");
        ArrayNode result = json.createArrayNode();
        for (UUID id : ids.stream().distinct().toList()) {
            MediaAsset asset = media.findById(id)
                    .filter(item -> item.getStatus() == MediaAssetStatus.READY)
                    .orElseThrow(() -> new ResourceNotFoundException("Uno de los archivos no esta disponible."));
            ObjectNode item = result.addObject();
            item.put("id", asset.getId().toString());
            item.put("name", asset.getFileName());
            item.put("type", asset.getType() == null ? "" : asset.getType().name());
            item.put("sizeBytes", asset.getSizeBytes() == null ? 0 : asset.getSizeBytes());
        }
        return result.toString();
    }

    private JsonNode findTool(JsonNode tools, String name) {
        if (tools != null) for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) return tool;
        }
        throw new IllegalArgumentException("El modelo solicito una herramienta que no esta disponible.");
    }

    private Policy policy(JsonNode tool) {
        JsonNode source = tool.path("_meta").path("picale/policy");
        if (!source.isObject()) throw new IllegalStateException("Una herramienta MCP no declaro su politica.");
        return new Policy(source.path("riskLevel").asInt(3), source.path("permission").asText("UNSPECIFIED"),
                source.path("confirmation").asText("explicit"));
    }

    private void requirePermission(User user, String permission) {
        if (permission == null || permission.equals("MEMBERSHIP")) return;
        try {
            if (!permissions.permisosDe(user).contains(Permission.valueOf(permission))) {
                throw new SecurityException("No tienes el permiso necesario para ejecutar esa accion.");
            }
        } catch (IllegalArgumentException e) {
            throw new SecurityException("La herramienta exige un permiso que el sistema no reconoce.");
        }
    }

    private AssistantMessage confirmationMessage(UUID threadId, UUID runId, AssistantToolCall call,
            JsonNode definition, String rawToken, String confirmation) {
        ObjectNode card = json.createObjectNode();
        card.put("type", "confirmation");
        card.put("runId", runId.toString());
        card.put("toolCallId", call.getId().toString());
        card.put("riskLevel", call.getRiskLevel());
        card.put("confirmation", confirmation);
        card.put("title", confirmation.equals("cost_notice") ? "Esta accion puede consumir creditos"
                : "Confirma antes de continuar");
        card.put("description", definition.path("description").asText("Se ejecutara la accion solicitada."));
        card.set("arguments", sanitize(parse(call.getArgumentsJson())));
        ArrayNode cards = json.createArrayNode().add(card);
        return AssistantMessage.builder().threadId(threadId).runId(runId).role("ASSISTANT")
                .content(card.path("title").asText() + ". Revisa los detalles antes de autorizarla.")
                .artifactsJson(cards.toString()).createdAt(LocalDateTime.now()).build();
    }

    private String resultArtifacts(UUID runId, JsonNode tools) {
        ArrayNode artifacts = json.createArrayNode();
        for (AssistantToolCall call : calls.findByRunIdOrderByCreatedAtAsc(runId)) {
            if (call.getStatus() != AssistantToolCallStatus.COMPLETED) continue;
            // Las consultas de lectura alimentan la respuesta redactada por el asistente.
            // Mostrar además su recibo técnico duplica la información y expone el formato MCP.
            if (call.getRiskLevel() == 0) continue;
            JsonNode definition = findTool(tools, call.getToolName());
            ObjectNode card = artifacts.addObject();
            card.put("type", "result");
            card.put("toolCallId", call.getId().toString());
            card.put("title", definition.path("description").asText("Resultado verificado"));
            card.put("summary", call.getResultSummary() == null ? "Accion completada." : call.getResultSummary());
            card.put("riskLevel", call.getRiskLevel());
            card.put("status", "completed");
        }
        return artifacts.isEmpty() ? null : artifacts.toString();
    }

    private ObjectNode assistantToolRequest(AssistantToolCall call) {
        ObjectNode message = json.createObjectNode();
        message.put("role", "assistant");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode requested = toolCalls.addObject();
        requested.put("id", call.getId().toString());
        requested.put("type", "function");
        ObjectNode function = requested.putObject("function");
        function.put("name", call.getToolName());
        function.put("arguments", call.getArgumentsJson());
        return message;
    }

    private ObjectNode toolResult(AssistantToolCall call, JsonNode result) {
        return toolResult(call.getId().toString(), result);
    }

    private ObjectNode toolResult(String providerCallId, JsonNode result) {
        ObjectNode message = json.createObjectNode();
        message.put("role", "tool");
        message.put("tool_call_id", providerCallId);
        message.put("content", truncate(result.toString(), 40_000));
        return message;
    }

    private void completeCall(AssistantRun run, AssistantToolCall call, JsonNode result, long started) {
        call.setStatus(AssistantToolCallStatus.COMPLETED);
        call.setDurationMs((System.nanoTime() - started) / 1_000_000);
        JsonNode safeResult = sanitize(result);
        call.setResultSummary(truncate(resultSummary(safeResult), 1000));
        calls.save(call);
        events.emit(run, "tool.completed", payload("toolCallId", call.getId().toString()));
    }

    /** Convierte la envoltura MCP en texto legible para recibos y auditoría. */
    private String resultSummary(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : content) {
                String value = item.path("text").asText("").strip();
                if (value.isEmpty()) continue;
                if (!text.isEmpty()) text.append('\n');
                text.append(value);
            }
            if (!text.isEmpty()) return text.toString();
        }
        return result.toString();
    }

    private void fail(AssistantRun run, String safeMessage) {
        fail(run, safeMessage, null);
    }

    /**
     * El texto que ve la persona nunca lleva detalles tecnicos, asi que la causa real solo
     * sobrevive si queda en el log: sin esto un turno fallido es imposible de diagnosticar.
     */
    private void fail(AssistantRun run, String safeMessage, Exception cause) {
        if (cause != null) {
            log.error("Turno del asistente fallido. run={} thread={} workspace={} modelo={}",
                    run.getId(), run.getThreadId(), run.getWorkspaceId(), run.getModel(), cause);
        }
        run.setStatus(AssistantRunStatus.FAILED);
        run.setErrorCode("ASSISTANT_RUN_FAILED");
        run.setErrorMessage(safeMessage);
        run.setFinishedAt(LocalDateTime.now());
        runs.save(run);
        messages.save(AssistantMessage.builder().threadId(run.getThreadId()).runId(run.getId()).role("ASSISTANT")
                .content(safeMessage).createdAt(LocalDateTime.now()).build());
        events.emit(run, "run.failed", payload("message", safeMessage));
    }

    private AssistantRun ownedRun(User user, UUID runId) {
        UUID workspaceId = user == null || user.getWorkspace() == null ? null : user.getWorkspace().getId();
        return runs.findById(runId).filter(run -> workspaceId != null && workspaceId.equals(run.getWorkspaceId())
                        && user.getId().equals(run.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Esa ejecucion no esta disponible."));
    }

    private AssistantRun ownedRunLocked(User user, UUID runId) {
        UUID workspaceId = user == null || user.getWorkspace() == null ? null : user.getWorkspace().getId();
        return runs.findLockedById(runId).filter(run -> workspaceId != null && workspaceId.equals(run.getWorkspaceId())
                        && user.getId().equals(run.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Esa ejecucion no esta disponible."));
    }

    private void updateThread(AssistantThread thread, String content, LocalDateTime now) {
        if ("Nueva conversacion".equals(thread.getTitle())) {
            thread.setTitle(truncate(content.replaceAll("\\s+", " ").strip(), 80));
        }
        thread.setUpdatedAt(now);
        threads.save(thread);
    }

    private JsonNode sanitize(JsonNode source) {
        JsonNode copy = source == null ? json.createObjectNode() : source.deepCopy();
        redact(copy);
        return copy;
    }

    private void redact(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT);
                if (key.contains("secret") || key.contains("password") || key.contains("token")
                        || key.contains("authorization")) {
                    ((ObjectNode) node).put(field.getKey(), "[REDACTED]");
                } else if (field.getValue().isTextual() && looksSigned(field.getValue().asText())) {
                    ((ObjectNode) node).put(field.getKey(), "[SIGNED_URL_REDACTED]");
                } else redact(field.getValue());
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                if (node.get(index).isTextual() && looksSigned(node.get(index).asText())) {
                    ((ArrayNode) node).set(index, json.getNodeFactory().textNode("[SIGNED_URL_REDACTED]"));
                } else redact(node.get(index));
            }
        }
    }

    private static boolean looksSigned(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("x-amz-") || lower.contains("x-goog-signature=")
                || lower.contains("signature=") || lower.contains("access_token=");
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (Exception e) { return json.createObjectNode(); }
    }

    private ObjectNode payload(String key, String value) {
        return json.createObjectNode().put(key, value);
    }

    private static String cleanContent(String value) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank()) throw new IllegalArgumentException("Escribe un mensaje para continuar.");
        if (result.length() > AssistantMessage.MAX_CONTENT) {
            throw new IllegalArgumentException("El mensaje es demasiado largo.");
        }
        return result;
    }

    private static String cleanKey(String value) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank() || result.length() > 100 || !result.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("Falta una clave de idempotencia valida.");
        }
        return result;
    }

    private static String cleanPromptName(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (clean.length() > 100 || !clean.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Ese atajo del asistente no existe.");
        }
        return clean;
    }

    private static String cleanPath(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replaceAll("[\\p{Cntrl}]", "").strip();
        return truncate(clean, 300);
    }

    private static String safeError(Exception error) {
        if (error instanceof IllegalArgumentException || error instanceof SecurityException
                || error instanceof AssistantMcpClient.McpException
                || error instanceof ResourceNotFoundException) {
            return truncate(error.getMessage(), 500);
        }
        // Que el servicio del modelo se caiga no es lo mismo que un error de la cuenta:
        // decirlo por su nombre evita que la persona crea que hizo algo mal.
        if (error instanceof RestClientException) {
            return "Ahora mismo no pude conectarme para pensar tu respuesta. No cambie nada; "
                    + "vuelve a intentarlo en un momento.";
        }
        return "No pude completar esta solicitud. No cambie nada en tu cuenta; intenta de nuevo.";
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo proteger la confirmacion.", e);
        }
    }

    private static boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record Policy(int riskLevel, String permission, String confirmation) {
        boolean requiresConfirmation() {
            return riskLevel > 0 || !"none".equals(confirmation);
        }
    }
}
