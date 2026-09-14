package com.metricol.api.enums;

/**
 * En qué punto va un trabajo de la cola de publicación.
 *
 * <p>La cola vive en la base de datos y no en memoria a propósito: si el
 * proceso se reinicia a mitad de una tanda, los trabajos siguen ahí y otro
 * worker los recoge. Una {@code BlockingQueue} en memoria habría perdido
 * todo lo pendiente en cada despliegue.
 */
public enum PublishJobStatus {
    /** Esperando turno. */
    QUEUED,

    /** Reclamado por un worker, en ejecución. */
    RUNNING,

    /** Falló de forma recuperable y volverá a intentarse en {@code runAt}. */
    RETRYING,

    /**
     * Aplazado a propósito, no por un fallo: la cuota diaria de las redes de
     * destino ya estaba agotada, así que vuelve a la cola cuando el contador
     * se reinicie.
     */
    DEFERRED,

    SUCCEEDED,

    /** Se agotaron los intentos, o falló por algo que reintentar no arregla. */
    FAILED,

    /** La publicación se borró o se editó antes de que le tocara salir. */
    CANCELED
}
