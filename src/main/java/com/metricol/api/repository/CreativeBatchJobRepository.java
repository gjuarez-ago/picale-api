package com.metricol.api.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.CreativeBatchJob;

public interface CreativeBatchJobRepository extends JpaRepository<CreativeBatchJob, UUID> {
    Optional<CreativeBatchJob> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Optional<CreativeBatchJob> findByRequestHash(String requestHash);
    List<CreativeBatchJob> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    @Query("""
            select j.id from CreativeBatchJob j
            where j.status = com.metricol.api.enums.CreativeBatchStatus.PENDING and j.runAt <= :now
            order by j.runAt asc, j.createdAt asc
            """)
    List<UUID> findDue(@Param("now") LocalDateTime now, Limit limit);

    @Modifying
    @Query("""
            update CreativeBatchJob j set j.status = com.metricol.api.enums.CreativeBatchStatus.RUNNING,
            j.lockedBy = :worker, j.lockedAt = :now, j.startedAt = coalesce(j.startedAt, :now),
            j.attempts = j.attempts + 1
            where j.id = :id and j.status = com.metricol.api.enums.CreativeBatchStatus.PENDING and j.runAt <= :now
            """)
    int claim(UUID id, String worker, LocalDateTime now);

    @Modifying
    @Query("""
            update CreativeBatchJob j set j.status = com.metricol.api.enums.CreativeBatchStatus.PENDING,
            j.lockedBy = null, j.lockedAt = null, j.runAt = :now, j.lastError = :reason
            where j.status = com.metricol.api.enums.CreativeBatchStatus.RUNNING and j.lockedAt < :limit
            and j.attempts < j.maxAttempts
            """)
    int rescue(LocalDateTime limit, LocalDateTime now, String reason);

    @Modifying
    @Query("""
            update CreativeBatchJob j set j.status = com.metricol.api.enums.CreativeBatchStatus.FAILED,
            j.finishedAt = :now, j.lockedBy = null, j.lockedAt = null, j.lastError = :reason
            where j.status = com.metricol.api.enums.CreativeBatchStatus.RUNNING and j.lockedAt < :limit
            and j.attempts >= j.maxAttempts
            """)
    int failExhausted(LocalDateTime limit, LocalDateTime now, String reason);
}
