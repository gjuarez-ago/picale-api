package com.metricol.api.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.metricol.api.entity.AssistantGenerationRequest;

public interface AssistantGenerationRequestRepository extends JpaRepository<AssistantGenerationRequest, UUID> {
    Optional<AssistantGenerationRequest> findByWorkspaceIdAndRequestId(UUID workspaceId, UUID requestId);
}
