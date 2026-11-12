package com.metricol.api.service.storage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.exception.ConflictoException;
import com.metricol.api.repository.MediaAssetRepository;

/** Una admisión de Create por intención. SENT incierto sólo reconcilia, jamás vuelve a crear. */
@Service
public class AperturaDeSubidas {
    private final MediaAssetRepository repository;
    private final R2StorageService storage;
    private final TransactionTemplate tx;
    public AperturaDeSubidas(MediaAssetRepository repository, R2StorageService storage, PlatformTransactionManager manager) {
        this.repository = repository; this.storage = storage;
        tx = new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MediaAsset resolver(UUID id, UUID workspace) {
        UUID token = tx.execute(s -> {
            MediaAsset a = repository.findForUploadUpdate(id).orElseThrow();
            IdentidadDeSubida.acceso(a, workspace);
            if (!"RESERVED".equals(a.getUploadOpeningState()) || a.getUploadDecision() != null
                    || a.getStatus() != MediaAssetStatus.PENDING) return null;
            if (a.getUploadExpiresAt() != null && !a.getUploadExpiresAt().isAfter(Instant.now())) {
                throw new ConflictoException("UPLOAD_EXPIRED", "El plazo de esta apertura terminó.");
            }
            UUID lease = UUID.randomUUID();
            a.setUploadOpeningState("SENT"); a.setUploadLeaseOwner(lease);
            a.setUploadLeaseUntil(Instant.now().plus(CoordinadorDeSubidas.LEASE));
            repository.save(a); return lease;
        });
        MediaAsset a = buscar(id, workspace);
        if (!"SENT".equals(a.getUploadOpeningState())) return a;
        if (token != null) {
            try {
                String uploadId = storage.iniciarPorPartes(a.getStorageKey(), a.getContentType());
                if (uploadId == null || uploadId.isBlank()) throw new IllegalStateException("R2 no devolvió identificador de apertura.");
                return adjuntar(id, workspace, uploadId);
            } catch (RuntimeException ex) {
                diferir(id, token);
                throw ex;
            }
        }
        if (a.getUploadLeaseUntil() != null && a.getUploadLeaseUntil().isAfter(Instant.now())) return a;
        List<String> ids = storage.aperturasPorClave(a.getStorageKey());
        if (ids.isEmpty()) {
            diferir(id, null);
            return buscar(id, workspace); // Ausencia no prueba que Create nunca fue admitido.
        }
        String elegida = tx.execute(s -> {
            MediaAsset actual = repository.findForUploadUpdate(id).orElseThrow();
            IdentidadDeSubida.acceso(actual, workspace);
            if (actual.getMultipartUploadId() != null) return actual.getMultipartUploadId();
            if (!"SENT".equals(actual.getUploadOpeningState()) || actual.getStatus() != MediaAssetStatus.PENDING) {
                throw new ConflictoException("UPLOAD_OPENING_CONFLICT", "La apertura cambió durante la reconciliación.");
            }
            actual.setMultipartUploadId(ids.get(0)); repository.save(actual); return ids.get(0);
        });
        // No se entregan firmas hasta terminar la reconciliación. Si abortar falla,
        // SENT + clave persisten y permiten descubrir/reintentar los sobrantes.
        for (String extra : ids) if (!elegida.equals(extra)) storage.abortarPorPartes(a.getStorageKey(), extra);
        return adjuntar(id, workspace, elegida);
    }
    private MediaAsset adjuntar(UUID id, UUID workspace, String uploadId) {
        return tx.execute(s -> {
            MediaAsset a = repository.findForUploadUpdate(id).orElseThrow(); IdentidadDeSubida.acceso(a, workspace);
            if ("ATTACHED".equals(a.getUploadOpeningState())) {
                if (!uploadId.equals(a.getMultipartUploadId())) throw new ConflictoException("UPLOAD_OPENING_CONFLICT", "La apertura requiere reconciliación.");
                return a;
            }
            if (!"SENT".equals(a.getUploadOpeningState()) || a.getStatus() != MediaAssetStatus.PENDING) {
                throw new ConflictoException("UPLOAD_OPENING_CONFLICT", "La apertura ya cambió de estado.");
            }
            if (a.getMultipartUploadId() != null && !uploadId.equals(a.getMultipartUploadId())) {
                throw new ConflictoException("UPLOAD_OPENING_CONFLICT", "Otra reconciliación ya eligió esta apertura.");
            }
            a.setMultipartUploadId(uploadId); a.setUploadOpeningState("ATTACHED");
            a.setUploadLeaseOwner(null); a.setUploadLeaseUntil(null); a.setUploadLastError(null);
            a.setUploadNextAttemptAt(Instant.now().plusSeconds(180)); return repository.save(a);
        });
    }
    private void diferir(UUID id, UUID token) {
        tx.executeWithoutResult(s -> repository.findForUploadUpdate(id).ifPresent(a -> {
            if (!"SENT".equals(a.getUploadOpeningState())) return;
            if (token != null && !token.equals(a.getUploadLeaseOwner())) return;
            a.setUploadLeaseUntil(null); a.setUploadLastError("OPENING_UNKNOWN");
            a.setUploadNextAttemptAt(Instant.now().plusSeconds(120)); repository.save(a);
        }));
    }
    private MediaAsset buscar(UUID id, UUID workspace) {
        MediaAsset a = repository.findById(id).orElseThrow(); IdentidadDeSubida.acceso(a, workspace); return a;
    }
}
