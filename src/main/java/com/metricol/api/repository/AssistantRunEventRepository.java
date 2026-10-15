package com.metricol.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.AssistantRunEvent;

public interface AssistantRunEventRepository extends JpaRepository<AssistantRunEvent, UUID> {
    List<AssistantRunEvent> findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(UUID runId, long sequence);
    long countByRunId(UUID runId);
}
