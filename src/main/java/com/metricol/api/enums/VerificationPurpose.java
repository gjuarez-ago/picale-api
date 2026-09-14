package com.metricol.api.enums;

/**
 * Para qué se emitió un código de seis dígitos.
 *
 * <p>Hoy solo hay uno, y el enum existe igual: en vivento366 empezó así y
 * acabó con tres —alta, restablecer, entrar sin contraseña— compartiendo cola
 * hasta que se separaron, porque pedir el de un flujo invalidaba el del otro
 * y el cooldown de uno frenaba al otro. Aquí se separan desde el primer día
 * para que el segundo uso no tenga que reescribir la tabla.
 */
public enum VerificationPurpose {
    /** Restablecer la contraseña desde «olvidé mi contraseña». */
    PASSWORD_RESET
}
