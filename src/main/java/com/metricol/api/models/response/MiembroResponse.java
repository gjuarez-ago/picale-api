package com.metricol.api.models.response;

import java.util.List;
import java.util.UUID;

import com.metricol.api.enums.OrgRole;

/**
 * Una persona del equipo, con lo que tiene en cada espacio.
 *
 * @param orgRole    su papel en la organización
 * @param eresTu     para que la pantalla no te deje quitarte a ti mismo
 * @param orgPermissions invitar gente o crear espacios, ya resueltos por su papel
 * @param espacios   a qué entra y con qué permisos; vacío si administra la
 *                   organización, porque entonces entra a todos
 */
public record MiembroResponse(UUID userId, String name, String email, OrgRole orgRole, boolean eresTu,
        List<String> orgPermissions, List<AccesoResponse> espacios) {

    /**
     * El acceso a un espacio concreto.
     *
     * @param permisos los que tiene DE VERDAD ahí: los de su rol, más los
     *                 dados, menos los quitados. La pantalla enseña esto y no
     *                 la resta, porque es lo que la persona puede hacer.
     */
    public record AccesoResponse(UUID workspaceId, String workspaceName, String role,
            List<String> permisos, List<String> extraPermissions, List<String> deniedPermissions) {
    }
}
