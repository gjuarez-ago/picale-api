package com.metricol.api.models.request;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.metricol.api.enums.OrgRole;
import com.metricol.api.enums.Permission;
import com.metricol.api.enums.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** A quién se invita, con qué papel y a qué espacios. */
@Getter
@Setter
public class InvitacionRequest {

    @NotBlank(message = "Escribe el correo de quien quieres invitar.")
    @Email(message = "Ese correo no parece válido.")
    private String email;

    /** `MEMBER` si no viene: lo normal es invitar a alguien que no administra. */
    private OrgRole orgRole;

    /** Los espacios que se le asignan. Vacío para un administrador. */
    private List<EspacioAsignado> workspaces;

    /** Nunca null, para que quien la recorra no tenga que comprobarlo. */
    public List<EspacioAsignado> espacios() {
        return workspaces == null ? new ArrayList<>() : workspaces;
    }

    /** Un espacio de la invitación, con lo que podrá hacer ahí. */
    @Getter
    @Setter
    public static class EspacioAsignado {

        private UUID workspaceId;

        /** `EDITOR` si no viene: quien entra a un cliente suele venir a trabajar. */
        private Role role;

        /** Permisos sueltos que se le dan por encima de su rol. */
        private Set<Permission> extraPermissions;

        /** Permisos que se le quitan aunque su rol los traiga. */
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
}
