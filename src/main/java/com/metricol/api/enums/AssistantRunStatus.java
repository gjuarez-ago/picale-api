package com.metricol.api.enums;

/** Estado durable de un turno del asistente operativo. */
public enum AssistantRunStatus {
    QUEUED,
    RUNNING,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED
}
