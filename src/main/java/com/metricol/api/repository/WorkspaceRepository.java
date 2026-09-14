package com.metricol.api.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.Workspace;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
}
