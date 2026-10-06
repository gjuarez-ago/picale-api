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
 * @param grupo     de quién son, para el selector cuando ve negocios de más de
 *                  un dueño ("Tus negocios", "Negocios de Juan…"); nulo si todos son del mismo
 */
public record MiWorkspaceResponse(UUID id, String name, String logoUrl, String color, List<String> tags,
        Role role, List<String> permisos, boolean activo, boolean archivado, String grupo) {

    public MiWorkspaceResponse(UUID id, String name, String logoUrl, String color, List<String> tags, Role role,
            List<String> permisos, boolean activo, boolean archivado) {
        this(id, name, logoUrl, color, tags, role, permisos, activo, archivado, null);
    }

    public MiWorkspaceResponse conGrupo(String grupo) {
        return new MiWorkspaceResponse(id, name, logoUrl, color, tags, role, permisos, activo, archivado, grupo);
    }
}
