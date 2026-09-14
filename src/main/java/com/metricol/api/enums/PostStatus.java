package com.metricol.api.enums;

public enum PostStatus {
    DRAFT,
    SCHEDULED,

    /**
     * Aceptada y esperando su turno en la cola. Es el estado en el que sale
     * la respuesta de "publicar ahora": el trabajo de verdad —descargar el
     * medio, hablar con upload-post, esperar a cada red— lo hace un worker
     * de fondo, así que la petición no se queda colgada un minuto.
     */
    QUEUED,

    /** Un worker la tiene en la mano ahora mismo. */
    PUBLISHING,

    PUBLISHED,
    FAILED
}
