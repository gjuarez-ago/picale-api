package com.metricol.api.exception;

/**
 * Se pidió otro código de seis dígitos demasiado pronto.
 *
 * <p>Hereda de {@link IllegalArgumentException} para que el manejador global
 * la conteste como un 400 con su mensaje, sin tocarlo.
 *
 * <p>Existe aparte por un solo caso: en «olvidé mi contraseña» la respuesta
 * tiene que ser la misma exista el correo o no. Frenar el reenvío está bien,
 * pero <b>decirlo</b> convertiría la pantalla en un detector de correos
 * registrados —solo quien existe puede tener un código reciente que frenar—.
 * Ahí se atrapa esta y se contesta lo de siempre.
 */
public class CodeCooldownException extends IllegalArgumentException {

    public CodeCooldownException(String message) {
        super(message);
    }
}
