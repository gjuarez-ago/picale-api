package com.metricol.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.AssistantToolCall;

public interface AssistantToolCallRepository extends JpaRepository<AssistantToolCall, UUID> {
    List<AssistantToolCall> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
