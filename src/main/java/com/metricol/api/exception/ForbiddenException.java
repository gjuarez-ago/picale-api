package com.metricol.api.exception;

/**
 * La persona está autenticada y es miembro del workspace, pero no tiene el
 * permiso que la acción pide.
 *
 * <p>Distinta de {@link ResourceNotFoundException} a propósito. Un 404 se usa
 * para lo que ni siquiera debería saber que existe —el workspace de otro
 * cliente—; este 403 es para lo que sí ve pero no puede tocar, y ahí decirle
 * exactamente qué le falta es lo que le permite pedirlo a su administrador.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
