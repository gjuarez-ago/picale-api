package com.metricol.api.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.CreditMovement;

public interface CreditMovementRepository extends JpaRepository<CreditMovement, UUID> {

    Optional<CreditMovement> findByWorkspaceIdAndMotivoAndReferencia(UUID workspaceId, String motivo, String referencia);

    boolean existsByWorkspaceIdAndMotivoAndReferencia(UUID workspaceId, String motivo, String referencia);
}
