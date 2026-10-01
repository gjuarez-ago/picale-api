package com.metricol.api.service.media;

import org.springframework.stereotype.Component;

import com.metricol.api.entity.MediaAsset;

/**
 * Orientación y duración de un video de Contenido, para que el agente decida
 * a qué formato y a qué redes va. Lee su URL pública con ffprobe: no baja el
 * video entero.
 */
@Component
public class MedidorDeVideo {

    private final FfmpegImagen ffmpeg;

    public MedidorDeVideo(FfmpegImagen ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /** Las medidas, o {@code null} si no se pudo leer. Nunca lanza. */
    public FfmpegImagen.MedidasVideo medir(MediaAsset video) {
        try {
            return video.getUrl() == null ? null : ffmpeg.medirVideo(video.getUrl());
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
