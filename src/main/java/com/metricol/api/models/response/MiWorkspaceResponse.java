package com.metricol.api.models.response;

import java.util.UUID;

import com.metricol.api.enums.Role;

/**
 * Uno de los workspaces a los que un usuario tiene acceso.
 *
 * @param role   su rol en ESE workspace
 * @param activo si es el que está usando ahora
 */
public record MiWorkspaceResponse(UUID id, String name, String logoUrl, Role role, boolean activo) {
}
