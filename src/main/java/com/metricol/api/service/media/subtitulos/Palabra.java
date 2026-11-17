package com.metricol.api.service.media.subtitulos;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Una palabra dicha en el video y cuándo: lo que hace falta para iluminarla
 * justo cuando suena.
 *
 * @param inicio segundos desde el principio del video
 * @param fin    segundos; nunca antes que {@code inicio}
 * @param clave  si es de las importantes de su frase (una cifra, un nombre,
 *               la palabra que carga la idea): se pinta en color y más grande.
 *               No se escribe cuando es falsa, así lo guardado antes se lee
 *               y se escribe igual que siempre.
 */
public record Palabra(String texto, double inicio, double fin,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean clave) {

    public Palabra(String texto, double inicio, double fin) {
        this(texto, inicio, fin, false);
    }

    public Palabra comoClave(boolean marcada) {
        return marcada == clave ? this : new Palabra(texto, inicio, fin, marcada);
    }
}
