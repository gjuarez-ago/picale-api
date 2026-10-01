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

    /**
     * Multiplica todos los saldos. Es el paso de "1 crédito = 1 imagen" a
     * "1 crédito = $1": quien tenía 3 imágenes conserva 3 imágenes (15 créditos).
     * Corre una sola vez, desde {@code BillingConfig}.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update ImageCredits c set c.monthlyBalance = c.monthlyBalance * :factor, c.packBalance = c.packBalance * :factor")
    int multiplicarSaldos(@Param("factor") int factor);
}
