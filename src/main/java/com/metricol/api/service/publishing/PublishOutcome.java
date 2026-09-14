package com.metricol.api.service.publishing;

import java.time.LocalDateTime;

/**
 * Cómo acabó un intento de publicar, contado de forma que el runner pueda
 * decidir qué hacer con el trabajo sin volver a mirar la publicación.
 *
 * <p>La distinción que de verdad importa es entre lo que reintentar arregla y
 * lo que no. Un timeout del proveedor se arregla esperando; una red que
 * rechaza el contenido dirá lo mismo las cuatro veces, y reintentarlo solo
 * retrasa el aviso a la persona y gasta cuota. Meterlo todo en un "falló"
 * habría obligado a elegir entre no reintentar nada o reintentarlo todo.
 */
public record PublishOutcome(Result result, String detail, LocalDateTime retryAt) {

    public enum Result {
        /** Salió en todas las redes que se intentaron. */
        SUCCESS,

        /** Salió en unas y no en otras. Para el post cuenta como publicado. */
        PARTIAL,

        /** Se cayó algo de camino: vale la pena volver a intentarlo. */
        FAILED_RETRYABLE,

        /** Reintentar no cambiaría nada: falta configuración, o la red dijo no. */
        FAILED_PERMANENT,

        /** No se intentó: la cuota diaria estaba agotada. Vuelve mañana. */
        DEFERRED
    }

    public static PublishOutcome success(String detail) {
        return new PublishOutcome(Result.SUCCESS, detail, null);
    }

    public static PublishOutcome partial(String detail) {
        return new PublishOutcome(Result.PARTIAL, detail, null);
    }

    public static PublishOutcome retryable(String detail) {
        return new PublishOutcome(Result.FAILED_RETRYABLE, detail, null);
    }

    public static PublishOutcome permanent(String detail) {
        return new PublishOutcome(Result.FAILED_PERMANENT, detail, null);
    }

    public static PublishOutcome deferred(String detail, LocalDateTime retryAt) {
        return new PublishOutcome(Result.DEFERRED, detail, retryAt);
    }

    public boolean publicado() {
        return result == Result.SUCCESS || result == Result.PARTIAL;
    }
}
