package com.metricol.api.service.media.subtitulos;

/**
 * Una palabra dicha en el video y cuándo: lo que hace falta para iluminarla
 * justo cuando suena.
 *
 * @param inicio segundos desde el principio del video
 * @param fin    segundos; nunca antes que {@code inicio}
 */
public record Palabra(String texto, double inicio, double fin) {
}
