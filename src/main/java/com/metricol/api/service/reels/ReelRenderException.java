package com.metricol.api.service.reels;

/** Fallo esperado de descarga, FFmpeg, validación o almacenamiento. */
public class ReelRenderException extends RuntimeException {
    public ReelRenderException(String message) {
        super(message);
    }

    public ReelRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
