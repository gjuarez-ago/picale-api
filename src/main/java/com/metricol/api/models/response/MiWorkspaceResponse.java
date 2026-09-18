package com.metricol.api.models.response;

import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.Role;

/**
 * Uno de los workspaces a los que un usuario tiene acceso.
 *
 * @param role   su rol en ESE workspace
 * @param activo    si es el que está usando ahora
 * @param archivado archivado: no publica y solo lo ve quien administra
 */
public record MiWorkspaceResponse(UUID id, String name, String logoUrl, String color, List<String> tags,
        Role role, boolean activo, boolean archivado) {
}
