package com.metricol.api.enums;

public enum PostTargetStatus {
    /** Recién creada, todavía sin encolar (borrador o programada). */
    PENDING,

    /** Encolada: su publicación ya está pedida y espera turno. */
    QUEUED,

    /** El worker está publicando en esta red en este momento. */
    PUBLISHING,

    PUBLISHED,
    FAILED,

    /**
     * No se intentó, y no por un error: se topó con la cuota diaria de esa
     * red. Se distingue de {@link #FAILED} porque no hay nada roto que
     * arreglar —hay que esperar a que el contador se reinicie— y porque la
     * publicación se reintenta sola al día siguiente.
     */
    SKIPPED
}
