package com.metricol.api.models.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.OrgRole;

/** Una invitación que sigue esperando respuesta. */
public record InvitacionResponse(UUID id, String email, OrgRole orgRole, LocalDateTime createdAt,
        LocalDateTime expiresAt, String invitadoPor, List<String> espacios) {
}
