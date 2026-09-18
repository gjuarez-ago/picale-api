package com.metricol.api.models.request;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.metricol.api.enums.Permission;
import com.metricol.api.enums.Role;

import lombok.Getter;
import lombok.Setter;

/**
 * El acceso de una persona a un espacio, tal como queda después de guardar.
 *
 * <p>`tieneAcceso` en false quita la membresía. Se manda así —y no con un
 * borrado aparte— porque la pantalla es una lista de casillas: destildar un
 * espacio y guardar es una sola acción para quien la usa.
 */
@Getter
@Setter
public class AccesoRequest {

    private UUID workspaceId;

    private boolean tieneAcceso;

    private Role role;

    private Set<Permission> extraPermissions;

    private Set<Permission> deniedPermissions;

    public Role rolONormal() {
        return role == null ? Role.EDITOR : role;
    }

    public Set<Permission> extras() {
        return extraPermissions == null ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(extraPermissions);
    }

    public Set<Permission> negados() {
        return deniedPermissions == null ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(deniedPermissions);
    }
}
