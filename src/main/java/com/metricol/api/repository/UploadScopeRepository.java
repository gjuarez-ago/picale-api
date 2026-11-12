package com.metricol.api.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import com.metricol.api.entity.UploadScope;

public interface UploadScopeRepository extends JpaRepository<UploadScope, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UploadScope s where s.id = :id")
    Optional<UploadScope> bloquear(UUID id);
}
