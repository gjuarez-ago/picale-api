package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.AssistantRun;

public interface AssistantRunRepository extends JpaRepository<AssistantRun, UUID> {
    List<AssistantRun> findByThreadIdOrderByCreatedAtAsc(UUID threadId);
    Optional<AssistantRun> findByWorkspaceIdAndIdempotencyKey(UUID workspaceId, String idempotencyKey);
}
