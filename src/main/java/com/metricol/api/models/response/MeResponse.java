package com.metricol.api.models.response;

import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.OrgRole;

/**
 * Quién es y qué puede hacer en el espacio donde está trabajando ahora.
 *
 * @param administraLaOrganizacion si puede crear espacios e invitar gente; es
 *                                 lo que decide si se enseña el menú de Equipo
 * @param orgPermissions           invitar gente o crear espacios, ya resueltos
 * @param role                     su rol en ESTE espacio
 * @param permisos                 lo que puede hacer aquí, ya resuelto
 * @param root                     si administra la plataforma entera (ver
 *                                 {@code User.platformAdmin}); es lo que decide
 *                                 si se enseña el menú de Administración
 */
public record MeResponse(UUID userId, String name, String email,
        UUID organizationId, String organizationName, OrgRole orgRole, boolean administraLaOrganizacion,
        List<String> orgPermissions,
        UUID workspaceId, String workspaceName, String role, List<String> permisos,
        boolean root) {
}
