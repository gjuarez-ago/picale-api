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

import com.metricol.api.entity.ReelRenderJob;
import com.metricol.api.enums.ReelRenderStatus;

public interface ReelRenderJobRepository extends JpaRepository<ReelRenderJob, UUID> {

    Optional<ReelRenderJob> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<ReelRenderJob> findByRecipeHash(String recipeHash);

    List<ReelRenderJob> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<ReelRenderJob> findByWorkspaceIdAndAgentManagedTrueAndAgentFinalizedAtIsNullOrderByCreatedAtAsc(
            UUID workspaceId);

    boolean existsByResultAssetIdInAndApprovedAtIsNull(List<UUID> resultAssetIds);

    @Query("""
            select j.id from ReelRenderJob j
            where j.status = com.metricol.api.enums.ReelRenderStatus.PENDING
              and j.runAt <= :now
            order by j.runAt asc, j.createdAt asc
            """)
    List<UUID> findDue(@Param("now") LocalDateTime now, Limit limit);

    @Modifying
    @Query("""
            update ReelRenderJob j
            set j.status = com.metricol.api.enums.ReelRenderStatus.RUNNING,
                j.lockedBy = :worker,
                j.lockedAt = :now,
                j.startedAt = coalesce(j.startedAt, :now),
                j.attempts = j.attempts + 1
            where j.id = :id
              and j.status = com.metricol.api.enums.ReelRenderStatus.PENDING
              and j.runAt <= :now
            """)
    int claim(@Param("id") UUID id, @Param("worker") String worker, @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            update ReelRenderJob j
            set j.status = com.metricol.api.enums.ReelRenderStatus.PENDING,
                j.lockedBy = null,
                j.lockedAt = null,
                j.runAt = :now,
                j.lastError = :reason
            where j.status = com.metricol.api.enums.ReelRenderStatus.RUNNING
              and j.lockedAt < :limit
              and j.attempts < j.maxAttempts
            """)
    int rescueAbandoned(@Param("limit") LocalDateTime limit, @Param("now") LocalDateTime now,
            @Param("reason") String reason);

    @Modifying
    @Query("""
            update ReelRenderJob j
            set j.status = com.metricol.api.enums.ReelRenderStatus.FAILED,
                j.finishedAt = :now,
                j.lockedBy = null,
                j.lockedAt = null,
                j.lastError = :reason
            where j.status = com.metricol.api.enums.ReelRenderStatus.RUNNING
              and j.lockedAt < :limit
              and j.attempts >= j.maxAttempts
            """)
    int failAbandonedExhausted(@Param("limit") LocalDateTime limit, @Param("now") LocalDateTime now,
            @Param("reason") String reason);

    @Modifying
    @Query("""
            update ReelRenderJob j
            set j.status = com.metricol.api.enums.ReelRenderStatus.CANCELLED,
                j.finishedAt = :now,
                j.lockedBy = null,
                j.lockedAt = null
            where j.id = :id and j.workspaceId = :workspaceId
              and j.status in :states
            """)
    int cancel(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId,
            @Param("states") List<ReelRenderStatus> states, @Param("now") LocalDateTime now);
}
