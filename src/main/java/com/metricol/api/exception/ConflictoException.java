package com.metricol.api.exception;

/**
 * Un conflicto con código propio: lo que se pidió es válido, pero choca con
 * el estado de las cosas y el cliente tiene que poder distinguir con qué.
 *
 * <p>Distinta de {@link IllegalStateException} —que también acaba en 409—
 * solo por el código: aquella contesta siempre {@code CONFLICT}, y hay
 * conflictos que la pantalla resuelve de otra forma según cuál sea. El primero
 * fue {@code MEDIA_IN_USE}: eliminar un archivo que alguna publicación usa no
 * es un error que enseñar en rojo, es una pregunta que hacer.
 */
public class ConflictoException extends RuntimeException {

    private final String code;
    private final java.util.Map<String, Object> detalles;

    public ConflictoException(String code, String message) {
        this(code, message, null);
    }

    /** @param detalles lo que la pantalla necesita además del mensaje (el archivo que ya estaba…) */
    public ConflictoException(String code, String message, java.util.Map<String, Object> detalles) {
        super(message);
        this.code = code;
        this.detalles = detalles;
    }

    public java.util.Map<String, Object> getDetalles() {
        return detalles;
    }

    public String getCode() {
        return code;
    }
}
