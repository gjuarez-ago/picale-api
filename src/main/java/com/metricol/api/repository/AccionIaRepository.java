package com.metricol.api.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.AccionIa;

public interface AccionIaRepository extends JpaRepository<AccionIa, UUID> {

    /** Lo que las IA hicieron en una cuenta desde una fecha, lo más reciente primero. */
    List<AccionIa> findByWorkspaceIdAndCreatedAtAfterOrderByCreatedAtDesc(UUID workspaceId, LocalDateTime desde,
            Pageable pagina);

    /** El rastro de una publicación o archivo concreto: quién hizo qué con él. */
    List<AccionIa> findByWorkspaceIdAndEntidadIdOrderByCreatedAtDesc(UUID workspaceId, String entidadId);
}
