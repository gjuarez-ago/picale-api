package com.metricol.api.service.assistant;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.metricol.api.entity.AssistantGenerationRequest;
import com.metricol.api.repository.AssistantGenerationRequestRepository;

/** Transacciones independientes del turno y del proveedor, a través de un bean separado. */
@Service
public class AssistantGenerationRequestStore {
    private final AssistantGenerationRequestRepository requests;
    public AssistantGenerationRequestStore(AssistantGenerationRequestRepository requests) { this.requests = requests; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserve(UUID workspace, UUID request, String fingerprint) {
        requests.saveAndFlush(AssistantGenerationRequest.builder().workspaceId(workspace).requestId(request)
                .fingerprint(fingerprint).createdAt(LocalDateTime.now()).build());
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<AssistantGenerationRequest> find(UUID workspace, UUID request) {
        return requests.findByWorkspaceIdAndRequestId(workspace, request);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accepted(UUID workspace, UUID request, String jobId) {
        var entry = requests.findByWorkspaceIdAndRequestId(workspace, request)
                .orElseThrow(() -> new IllegalStateException("No se encontró la reserva de generación."));
        entry.setJobId(jobId);
        requests.saveAndFlush(entry);
    }
}
