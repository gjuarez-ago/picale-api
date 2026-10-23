package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import com.metricol.api.entity.AssistantRun;

public interface AssistantRunRepository extends JpaRepository<AssistantRun, UUID> {
    List<AssistantRun> findByThreadIdOrderByCreatedAtAsc(UUID threadId);
    Optional<AssistantRun> findByWorkspaceIdAndIdempotencyKey(UUID workspaceId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AssistantRun r where r.id = :id")
    Optional<AssistantRun> findLockedById(UUID id);

    /**
     * Los turnos que quedaron a medias, de todos los workspaces: id y tenant. Nativa a
     * propósito, para leerlos al arrancar sin un tenant puesto.
     */
    @Query(value = "select cast(id as varchar), tenant_id from assistant_runs"
            + " where status in ('QUEUED', 'RUNNING') and created_at < :antes", nativeQuery = true)
    java.util.List<Object[]> findInterrumpidos(@org.springframework.data.repository.query.Param("antes")
            java.time.LocalDateTime antes);
}
