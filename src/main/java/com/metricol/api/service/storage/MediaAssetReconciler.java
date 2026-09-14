package com.metricol.api.service.storage;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.repository.MediaAssetRepository;

/**
 * Decide qué pasa con una subida que quedó a medias, preguntándole a R2.
 *
 * <p>Está separada de {@link MediaOrphanWorker} porque
 * {@code @Transactional} solo actúa cuando la llamada cruza el proxy de
 * Spring: un método anotado que el worker se llamara a sí mismo correría sin
 * transacción ninguna.
 *
 * <p><b>No toca el tenant, y es a propósito.</b> Quien lo impone es el
 * worker, ANTES de llamar aquí. El orden no es un detalle de estilo: Hibernate
 * resuelve el tenant cuando abre la sesión, así que ponerlo dentro de un
 * método transaccional llegaría tarde —la sesión ya estaría abierta como
 * GLOBAL— y la fila no se encontraría nunca.
 */
@Service
public class MediaAssetReconciler {

    private final MediaAssetRepository repository;
    private final R2StorageService storage;

    public MediaAssetReconciler(MediaAssetRepository repository, R2StorageService storage) {
        this.repository = repository;
        this.storage = storage;
    }

    /**
     * Confirma la subida si el archivo está en R2, o borra la fila si no.
     *
     * <p>Los dos finales son correctos. Si el archivo está, lo que se perdió
     * fue la llamada de confirmar y aquí se apunta el tamaño real: el archivo
     * aparece en la galería un rato tarde, que es mucho mejor que cobrarle a
     * alguien un archivo que no puede ver. Si no está, la subida no llegó a
     * pasar y el espacio apartado vuelve.
     *
     * @return {@code true} si se confirmó, {@code false} si se descartó.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean resolver(UUID assetId) {
        MediaAsset asset = repository.findById(assetId).orElse(null);
        if (asset == null) {
            return false;
        }
        if (asset.getStatus() == MediaAssetStatus.READY) {
            return true;
        }

        R2StorageService.Consulta enR2 = storage.consultar(asset.getStorageKey());
        if (enR2 == null) {
            repository.delete(asset);
            return false;
        }

        asset.setSizeBytes(enR2.sizeBytes());
        asset.setStatus(MediaAssetStatus.READY);
        if (enR2.contentType() != null && !enR2.contentType().isBlank()) {
            asset.setContentType(enR2.contentType());
        }
        repository.save(asset);
        return true;
    }
}
