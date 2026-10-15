package com.metricol.api.enums;

/** Estado de una llamada MCP asociada a un turno. */
public enum AssistantToolCallStatus {
    PROPOSED,
    WAITING_CONFIRMATION,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
