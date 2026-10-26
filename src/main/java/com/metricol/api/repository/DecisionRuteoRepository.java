package com.metricol.api.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.DecisionRuteo;

public interface DecisionRuteoRepository extends JpaRepository<DecisionRuteo, UUID> {

    List<DecisionRuteo> findByWorkspaceIdAndPostIdOrderByPlataforma(UUID workspaceId, UUID postId);

    List<DecisionRuteo> findByWorkspaceIdAndCreatedAtAfterOrderByCreatedAtDesc(
            UUID workspaceId, LocalDateTime desde);

    @Transactional
    void deleteByWorkspaceId(UUID workspaceId);
}
