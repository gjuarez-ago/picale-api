package com.metricol.api.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.metricol.api.entity.ContenidoJob;

public interface ContenidoJobRepository extends JpaRepository<ContenidoJob, UUID> {
    Optional<ContenidoJob> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ContenidoJob j where j.id=:id")
    Optional<ContenidoJob> locked(@Param("id") UUID id);
    @Query("select j.id from ContenidoJob j where j.activeWorkspace is not null and (j.status='QUEUED' or j.leaseUntil < :now) order by j.createdAt")
    List<UUID> dispatchable(@Param("now") Instant now, Pageable limit);
    @Modifying
    @Query("update ContenidoJob j set j.leaseUntil=:until where j.owner=:owner and j.activeWorkspace is not null and j.leaseUntil >= :now")
    int heartbeat(@Param("owner") UUID owner, @Param("now") Instant now, @Param("until") Instant until);
}
