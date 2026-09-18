package com.metricol.api.models.response;

import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.Role;

/**
 * Uno de los workspaces a los que un usuario tiene acceso.
 *
 * @param role     su rol en ESE workspace
 * @param permisos lo que puede hacer DE VERDAD ahí: el rol, más lo que se le dio
 *                 y menos lo que se le quitó a mano. El selector lo enseña; el
 *                 servidor sigue decidiendo en cada petición.
 * @param activo    si es el que está usando ahora
 * @param archivado archivado: no publica y solo lo ve quien administra
 */
public record MiWorkspaceResponse(UUID id, String name, String logoUrl, String color, List<String> tags,
        Role role, List<String> permisos, boolean activo, boolean archivado) {
}
