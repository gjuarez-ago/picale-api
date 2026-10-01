package com.metricol.api.service.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metricol.api.config.MediaAdaptProperties;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.storage.R2StorageService;

/**
 * El retoque del agente: la foto un poco más clara y viva, en un archivo nuevo.
 *
 * <p>No gasta créditos: es ffmpeg en el servidor. La original no se toca; la
 * copia se marca como de la IA para que el agente no la revise como material
 * nuevo del cliente.
 */
@Component
public class RetoqueDeFoto {

    private static final Logger log = LoggerFactory.getLogger(RetoqueDeFoto.class);

    private static final long MAX_BYTES = 25L * 1024 * 1024;

    private final FfmpegImagen ffmpeg;
    private final MediaAdaptProperties props;
    private final R2StorageService storage;
    private final MediaAssetRepository assets;

    public RetoqueDeFoto(FfmpegImagen ffmpeg, MediaAdaptProperties props, R2StorageService storage,
            MediaAssetRepository assets) {
        this.ffmpeg = ffmpeg;
        this.props = props;
        this.storage = storage;
        this.assets = assets;
    }

    /** La copia retocada, ya guardada, o {@code null} si no se pudo. Nunca lanza. */
    public MediaAsset retocar(MediaAsset foto, UUID workspaceId) {
        if (!props.isEnabled() || foto.getStorageKey() == null
                || (foto.getSizeBytes() != null && foto.getSizeBytes() > MAX_BYTES)) {
            return null;
        }
        Path original = null;
        try {
            original = Files.createTempFile("picale-original-", ".img");
            if (!storage.descargar(foto.getStorageKey(), original)) {
                return null;
            }
            byte[] retocada = ffmpeg.retocar(original, props.getCalidad());
            if (retocada == null) {
                return null;
            }
            String nombre = "retocada-" + foto.getFileName();
            String clave = storage.claveNueva(workspaceId, nombre.endsWith(".jpg") ? nombre : nombre + ".jpg",
                    "image/jpeg");
            String url = storage.subirBytes(clave, retocada, "image/jpeg");
            return assets.save(MediaAsset.builder()
                    .fileName(nombre)
                    .storageKey(clave)
                    .url(url)
                    .type(MediaType.IMAGE)
                    .contentType("image/jpeg")
                    .sizeBytes((long) retocada.length)
                    .status(MediaAssetStatus.READY)
                    .generadaPorIa(true)
                    .build());
        } catch (Exception ex) {
            log.warn("No se pudo retocar {}: {}", foto.getId(), ex.toString());
            return null;
        } finally {
            FfmpegImagen.borrar(original);
        }
    }
}
