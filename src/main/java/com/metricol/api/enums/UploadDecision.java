package com.metricol.api.enums;

/** Decisión durable separada del estado del medio; null equivale a sesión abierta heredada. */
public enum UploadDecision {
    CHECKING, COMPLETING, CANCELLING, EXPIRING, CANCELLED, EXPIRED, DONE
}
