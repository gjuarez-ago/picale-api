package com.metricol.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.AssistantMessage;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, UUID> {
    List<AssistantMessage> findByThreadIdOrderByCreatedAtAsc(UUID threadId);
}
