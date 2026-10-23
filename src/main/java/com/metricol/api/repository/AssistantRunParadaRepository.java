package com.metricol.api.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.AssistantRunParada;

public interface AssistantRunParadaRepository extends JpaRepository<AssistantRunParada, UUID> {
}
