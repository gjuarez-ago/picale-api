package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.ConexionIa;

public interface ConexionIaRepository extends JpaRepository<ConexionIa, UUID> {

    List<ConexionIa> findByUserIdOrderByCreadaEnDesc(UUID userId);

    Optional<ConexionIa> findByIdAndUserId(UUID id, UUID userId);
}
