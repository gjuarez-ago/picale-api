package com.metricol.api.service.campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.storage.R2StorageService;

/**
 * El logo del negocio listo para ponerlo encima de un video: un PNG
 * transparente del tamaño del video con el sello arriba (ver
 * {@link SelloDeLogo#capaParaVideo}). La cola de video lo pega con ffmpeg en
 * la misma pasada que los subtítulos y la música.
 */
@Component
public class LogoEnVideo {

    private static final Logger log = LoggerFactory.getLogger(LogoEnVideo.class);

    /** Un logo más pesado que esto no es un logo: no se baja. */
    private static final long MAX_BYTES = 15L * 1024 * 1024;

    private final R2StorageService storage;
    private final MediaAssetRepository assets;

    public LogoEnVideo(R2StorageService storage, MediaAssetRepository assets) {
        this.storage = storage;
        this.assets = assets;
    }

    /**
     * La capa con el logo, o {@code null} si no se pudo (el logo ya no está,
     * no se lee). Nunca lanza: el video sin logo es mejor que un video que
     * no sale.
     *
     * @param logoUrl  el logo de la marca, tal como está en Contenido
     * @param cuadro   un cuadro del video al tamaño del video
     * @param vertical si es un reel: se deja más aire arriba
     */
    public byte[] capa(String logoUrl, byte[] cuadro, boolean vertical) {
        if (logoUrl == null || logoUrl.isBlank() || cuadro == null) {
            return null;
        }
        MediaAsset logo = assets.findByUrlIn(List.of(logoUrl)).stream()
                .filter(a -> a.getType() == MediaType.IMAGE && a.getStorageKey() != null)
                .findFirst().orElse(null);
        if (logo == null || (logo.getSizeBytes() != null && logo.getSizeBytes() > MAX_BYTES)) {
            return null;
        }
        Path temporal = null;
        try {
            temporal = Files.createTempFile("picale-logo-video-", ".img");
            if (!storage.descargar(logo.getStorageKey(), temporal)) {
                return null;
            }
            return SelloDeLogo.capaParaVideo(cuadro, Files.readAllBytes(temporal), vertical);
        } catch (IOException | RuntimeException ex) {
            log.warn("No se pudo preparar el logo del video: {}", ex.getMessage());
            return null;
        } finally {
            if (temporal != null) {
                try {
                    Files.deleteIfExists(temporal);
                } catch (IOException ignorada) {
                    // Un temporal suelto no rompe nada.
                }
            }
        }
    }
}
