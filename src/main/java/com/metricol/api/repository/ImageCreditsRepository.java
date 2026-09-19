package com.metricol.api.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.ImageCredits;

import jakarta.persistence.LockModeType;

public interface ImageCreditsRepository extends JpaRepository<ImageCredits, UUID> {

    /** Con candado: dos generaciones a la vez no pueden gastar el mismo crédito. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ImageCredits c where c.workspaceId = :id")
    Optional<ImageCredits> bloquear(@Param("id") UUID workspaceId);
}
