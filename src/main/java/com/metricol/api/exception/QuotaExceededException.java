package com.metricol.api.exception;

/**
 * Se pidió algo que cabe perfectamente, solo que ya no hay sitio: el espacio
 * del workspace está lleno, o la red ya publicó todo lo que acepta hoy.
 *
 * <p>Separada de {@link IllegalArgumentException} a propósito. Un argumento
 * inválido es un error de quien llama y se arregla mandando otra cosa; esto
 * no tiene nada mal — se arregla borrando archivos o esperando— y merece su
 * propio código de error para que la app pueda ofrecer la salida correcta en
 * vez de un "revisa los datos".
 */
public class QuotaExceededException extends RuntimeException {

    private final String code;

    public QuotaExceededException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
