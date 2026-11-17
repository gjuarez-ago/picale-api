package com.metricol.api.service.media.edicion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.AnalisisHablanteProperties;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;

/** Instantánea de metadatos. No retiene una entidad JPA durante un proceso de video. */
@Service
public class FuenteDeVideoHablante {
    public record Fuente(UUID workspaceId, UUID assetId, String storageKey, long sizeBytes,
            String transcriptRevision) { }

    private final MediaAssetRepository assets;
    private final AnalisisHablanteProperties properties;

    public FuenteDeVideoHablante(MediaAssetRepository assets, AnalisisHablanteProperties properties) {
        this.assets = assets;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Fuente cargar(UUID assetId, UUID workspaceId) {
        if (assetId == null || workspaceId == null) throw new IllegalArgumentException("Falta el video o su cuenta.");
        var asset = assets.findById(assetId).filter(a -> workspaceId.toString().equals(a.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Ese video no está disponible en esta cuenta."));
        if (asset.getStatus() != MediaAssetStatus.READY || asset.getType() != MediaType.VIDEO
                || asset.getArchivedAt() != null || asset.getSourceAssetId() != null
                || asset.getTrimStartSeconds() != null || asset.getTrimEndSeconds() != null
                || asset.getSubtitulos() != null || Boolean.TRUE.equals(asset.getGeneradaPorIa())
                || Boolean.TRUE.equals(asset.getCreadaConIa())) {
            throw new IllegalArgumentException("El análisis requiere un video original disponible.");
        }
        String key = asset.getStorageKey();
        if (key == null || !key.startsWith("media/" + workspaceId + "/") || key.contains("..")
                || key.contains("\\") || key.indexOf('\0') >= 0 || key.endsWith("/")) {
            throw new IllegalArgumentException("El objeto no pertenece al directorio de esta cuenta.");
        }
        Long bytes = asset.getSizeBytes();
        if (bytes == null || bytes <= 0 || bytes > properties.getMaxSourceBytes()) {
            throw new IllegalArgumentException("El video excede el tamaño permitido para análisis.");
        }
        return new Fuente(workspaceId, assetId, key, bytes, revision(asset.getTranscripcion()));
    }

    private static String revision(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((texto == null ? "" : texto).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("No se pudo identificar la transcripción.", ex);
        }
    }
}
