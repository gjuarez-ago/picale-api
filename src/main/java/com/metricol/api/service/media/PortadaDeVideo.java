package com.metricol.api.service.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metricol.api.config.MediaAdaptProperties;

/**
 * La portada que se le manda a Instagram con cada reel.
 *
 * <p>Sin ella, Instagram elige la suya, y suele ser el primer cuadro: el que se
 * grabó mientras la mano aún se movía o el sensor no había ajustado la luz. Los
 * reels salían con portada negra en la cuadrícula del perfil. Es el mismo
 * motivo por el que {@link MiniaturaDeVideo} saca el cuadro del segundo uno.
 *
 * <p>No se reaprovecha esa miniatura: mide 480 de ancho, pensada para una
 * cuadrícula, y como portada de un reel se vería borrosa. Esta sale a tamaño de
 * publicación.
 */
@Component
public class PortadaDeVideo {

    private static final Logger log = LoggerFactory.getLogger(PortadaDeVideo.class);

    /** Mismo segundo que la miniatura, por la misma razón. */
    private static final String SEGUNDO = "1";

    /** El ancho de un reel. Instagram acepta hasta 8 MB; a este ancho sobra. */
    private static final int ANCHO = 1080;

    private final MediaAdaptProperties props;
    private final FfmpegImagen ffmpeg;

    public PortadaDeVideo(MediaAdaptProperties props, FfmpegImagen ffmpeg) {
        this.props = props;
        this.ffmpeg = ffmpeg;
    }

    /**
     * El JPEG de la portada, o {@code null} si no se pudo sacar.
     *
     * <p>Nunca lanza. Sin portada el reel se publica igual, con la que elija
     * Instagram: es lo que pasaba hasta ahora, y no puede costar la publicación.
     */
    public byte[] sacar(byte[] video) {
        return sacar(video, Double.parseDouble(SEGUNDO));
    }

    /**
     * La portada de un segundo concreto: el que eligió el agente. Si ese
     * segundo no existe, {@link FfmpegImagen#fotograma} cae al principio.
     */
    public byte[] sacar(byte[] video, double segundo) {
        if (!props.isEnabled() || video == null || video.length == 0) {
            return null;
        }
        Path archivo = null;
        try {
            archivo = Files.createTempFile("picale-portada-", ".mp4");
            Files.write(archivo, video);
            String cuando = String.format(java.util.Locale.US, "%.3f", Math.max(0, segundo));
            byte[] imagen = ffmpeg.fotograma(archivo, cuando, ANCHO, props.getCalidad());
            if (imagen == null) {
                log.warn("ffmpeg no devolvió portada para el video; Instagram elegirá la suya.");
            }
            return imagen;
        } catch (IOException ex) {
            log.warn("No se pudo escribir el temporal para sacar la portada: {}", ex.getMessage());
            return null;
        } finally {
            FfmpegImagen.borrar(archivo);
        }
    }
}
