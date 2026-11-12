package com.metricol.api.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.MediaAsset;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

public class MediaAssetUploadLocksImpl implements MediaAssetUploadLocks {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<MediaAsset> findForUploadUpdate(UUID id) {
        // La consulta aplica @TenantId y toma el bloqueo, pero puede devolver una
        // instancia ya administrada con valores viejos. FOR UPDATE no refresca L1.
        Optional<MediaAsset> encontrada = entityManager
                .createQuery("select m from MediaAsset m where m.id = :id", MediaAsset.class)
                .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst();
        encontrada.ifPresent(a -> entityManager.refresh(a, LockModeType.PESSIMISTIC_WRITE));
        return encontrada;
    }
}
