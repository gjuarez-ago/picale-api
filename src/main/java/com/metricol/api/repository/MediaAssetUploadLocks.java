package com.metricol.api.repository;

import java.util.Optional;
import java.util.UUID;
import com.metricol.api.entity.MediaAsset;

/** Lectura de decisión vigente, incluso con un contexto de petición abierto (OSIV). */
public interface MediaAssetUploadLocks {
    Optional<MediaAsset> findForUploadUpdate(UUID id);
}
