package com.metricol.api.service.storage;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.repository.MediaAssetRepository;

/**
 * Decide qué pasa con una subida que quedó a medias, preguntándole a R2.
 *
 * <p>El worker aplica el tenant antes de entrar. La consulta remota no tiene
 * transacción; la escritura simple usa una transacción corta y multipart
 * delega al coordinador durable. No se marca READY desde una copia desactualizada.
 */
@Service
public class MediaAssetReconciler {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetReconciler.class);

    private final MediaAssetRepository repository;
    private final R2StorageService storage;
    private final CoordinadorDeSubidas coordinador;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private com.metricol.api.service.MediaService media;

    public MediaAssetReconciler(MediaAssetRepository repository, R2StorageService storage,
            CoordinadorDeSubidas coordinador, org.springframework.transaction.PlatformTransactionManager manager) {
        this.repository = repository;
        this.storage = storage;
        this.coordinador = coordinador;
        tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setMedia(@org.springframework.context.annotation.Lazy com.metricol.api.service.MediaService media) { this.media = media; }

    /**
     * Confirma con el camino normal, limpia una reserva simple ausente o delega
     * multipart respetando decisión, lease, plazo y registro terminal.
     *
     * <p>Los dos finales son correctos. Si el archivo está, lo que se perdió
     * fue la llamada de confirmar y aquí se apunta el tamaño real: el archivo
     * aparece en la galería un rato tarde, que es mucho mejor que cobrarle a
     * alguien un archivo que no puede ver. Si no está, la subida no llegó a
     * pasar y el espacio apartado vuelve.
     *
     * @return {@code true} si se confirmó, {@code false} si se descartó.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean resolver(UUID assetId) {
        MediaAsset asset = repository.findById(assetId).orElse(null);
        if (asset == null) {
            return false;
        }
        if (asset.getStatus() == MediaAssetStatus.READY) {
            return true;
        }
        if (asset.getMultipartPartSize() != null) {
            try {
                return coordinador.limpiar(assetId, UUID.fromString(asset.getTenantId()));
            } catch (RuntimeException ex) {
                log.warn("Limpieza multipart {} pendiente ({})", assetId, ex.getClass().getSimpleName());
                return false;
            }
        }
        if (asset.getStatus() != MediaAssetStatus.PENDING) return false;

        R2StorageService.Consulta enR2 = storage.consultar(asset.getStorageKey());
        if (enR2 == null) {
            tx.executeWithoutResult(s -> repository.findForUploadUpdate(assetId).ifPresent(actual -> {
                if (actual.getStatus() == MediaAssetStatus.PENDING && actual.getMultipartPartSize() == null) {
                    repository.delete(actual);
                }
            }));
            return false;
        }

        // Do not undo the confirm endpoint's integrity check in background.
        // Keep the original/reservation for investigation or a same-ID retry.
        if (enR2.sizeBytes() <= 0 || (asset.getSizeBytes() != null
                && enR2.sizeBytes() < asset.getSizeBytes())) {
            return false;
        }
        media.confirm(assetId);
        return true;
    }
}
