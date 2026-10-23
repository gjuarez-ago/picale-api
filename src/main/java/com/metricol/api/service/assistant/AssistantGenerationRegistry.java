package com.metricol.api.service.assistant;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.models.request.CampaignImageRequest;

/** Una solicitud del asistente puede aceptar como máximo un trabajo de generación. */
@Service
public class AssistantGenerationRegistry {
    private final AssistantGenerationRequestStore store;
    private final ObjectMapper json;
    public AssistantGenerationRegistry(AssistantGenerationRequestStore store, ObjectMapper json) {
        this.store = store; this.json = json;
    }
    public String start(UUID workspace, UUID requestId, CampaignImageRequest request, Supplier<String> start) {
        String fingerprint;
        try { fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request))); }
        catch (Exception error) { throw new IllegalStateException("No se pudo identificar la solicitud de generación.", error); }
        // Camino habitual de un reenvío: no provocar otra violación de unicidad.
        var prior = store.find(workspace, requestId);
        if (prior.isPresent()) return reuse(prior.get(), fingerprint);
        try {
            store.reserve(workspace, requestId, fingerprint);
        } catch (DataIntegrityViolationException concurrentRequest) {
            // Otra petición ganó la reserva. Su transacción ya terminó; no ejecutar el proveedor.
            return reuse(store.find(workspace, requestId).orElseThrow(() -> concurrentRequest), fingerprint);
        }
        // Si esto falla o el proceso cae, la reserva sigue existiendo: no se repite a ciegas.
        String jobId = start.get();
        if (jobId == null || jobId.isBlank()) throw new IllegalStateException("La solicitud quedó reservada sin un trabajo verificable. Revisa su estado antes de repetirla.");
        store.accepted(workspace, requestId, jobId);
        return jobId;
    }
    private String reuse(com.metricol.api.entity.AssistantGenerationRequest prior, String fingerprint) {
        if (!fingerprint.equals(prior.getFingerprint()))
            throw new IllegalStateException("Esta solicitud ya fue registrada con otros parámetros. No se inició otra generación.");
        if (prior.getJobId() == null || prior.getJobId().isBlank())
            throw new IllegalStateException("La solicitud anterior tiene un resultado pendiente de verificar. No se volvió a generar; revisa Contenido antes de repetirla.");
        return prior.getJobId();
    }
}
