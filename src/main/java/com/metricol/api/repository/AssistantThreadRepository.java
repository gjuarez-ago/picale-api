package com.metricol.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.AssistantThread;

public interface AssistantThreadRepository extends JpaRepository<AssistantThread, UUID> {
    List<AssistantThread> findByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID workspaceId);
}
