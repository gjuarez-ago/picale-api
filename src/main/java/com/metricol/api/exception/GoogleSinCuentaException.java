package com.metricol.api.exception;

/**
 * Se intentó ENTRAR con una cuenta de Google que todavía no tiene cuenta aquí.
 *
 * <p>Existe porque entrar y registrarse dejaron de ser lo mismo. Antes
 * {@code /auth/google} creaba la cuenta si no la encontraba, así que cualquiera
 * con una cuenta de Google entraba a una plataforma en la que nunca se había
 * dado de alta: el registro —con su nombre de negocio, su giro y su objetivo—
 * se saltaba entero, y nadie podía decidir quién entra.
 *
 * <p>Su código propio es lo que permite a la app distinguir esto de un fallo:
 * no es un error que haya que arreglar, es el camino al registro, y con los
 * datos que Google ya entregó puede llevar allí el nombre y el correo puestos.
 */
public class GoogleSinCuentaException extends RuntimeException {

    public GoogleSinCuentaException(String message) {
        super(message);
    }
}
