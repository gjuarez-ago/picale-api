package com.metricol.api.service;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.Permission;
import com.metricol.api.enums.Role;
import com.metricol.api.exception.ForbiddenException;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * Quién puede hacer qué en el workspace en el que está trabajando.
 *
 * <p>Es el único sitio que contesta esa pregunta. Las pantallas piden la lista
 * para no ofrecer lo que va a ser rechazado, pero eso es cortesía: la decisión
 * de verdad se toma aquí, al recibir la petición, porque una petición se puede
 * mandar sin pasar por ninguna pantalla.
 *
 * <p>Sin membresía no hay permisos. Puede pasar si a alguien lo sacaron del
 * workspace mientras tenía la sesión abierta: su token sigue siendo válido
 * —no se pueden invalidar tokens ya emitidos— y lo que lo frena es justo esto.
 */
@Service
public class PermissionService {

    private final WorkspaceMemberRepository miembros;
    private final WorkspaceRepository workspaces;
    private final OrganizationMemberRepository orgMiembros;

    public PermissionService(WorkspaceMemberRepository miembros, WorkspaceRepository workspaces,
            OrganizationMemberRepository orgMiembros) {
        this.miembros = miembros;
        this.workspaces = workspaces;
        this.orgMiembros = orgMiembros;
    }

    /**
     * Lo que esta persona puede hacer en ese workspace.
     *
     * <p>Dos caminos, y el de arriba manda: quien administra la ORGANIZACIÓN
     * dueña del workspace lo puede todo ahí, sin estar apuntado como miembro.
     * Eso es lo que se compró con la capa de organización — dar de alta a un
     * socio una vez en lugar de treinta.
     *
     * <p>Para todos los demás vale su membresía del workspace, con el rol y
     * los ajustes que se le hayan puesto. Sin membresía, nada: puede pasar si
     * lo sacaron del espacio mientras tenía la sesión abierta.
     */
    public Set<Permission> permisosDe(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return EnumSet.noneOf(Permission.class);
        }

        if (administraLaOrganizacionDe(userId, workspaceId)) {
            return EnumSet.allOf(Permission.class);
        }

        return miembros.findByUserIdAndWorkspaceId(userId, workspaceId)
                .map(WorkspaceMember::permisosEfectivos)
                .orElseGet(() -> EnumSet.noneOf(Permission.class));
    }

    /** ¿Es dueño o administrador de la organización a la que pertenece este workspace? */
    public boolean administraLaOrganizacionDe(UUID userId, UUID workspaceId) {
        return workspaces.findById(workspaceId)
                .map(Workspace::getOrganization)
                .map(org -> orgMiembros.findByUserIdAndOrganizationId(userId, org.getId())
                        .map(m -> m.getRole().administraLaOrganizacion())
                        .orElse(false))
                .orElse(false);
    }

    /** Los del workspace en el que está trabajando ahora. */
    public Set<Permission> permisosDe(User user) {
        if (user == null || user.getWorkspace() == null) {
            return EnumSet.noneOf(Permission.class);
        }
        return permisosDe(user.getId(), user.getWorkspace().getId());
    }

    /** El rol de la persona en su workspace activo, para enseñarlo en la interfaz. */
    public Role rolDe(User user) {
        if (user == null || user.getWorkspace() == null) {
            return Role.VIEWER;
        }
        if (administraLaOrganizacionDe(user.getId(), user.getWorkspace().getId())) {
            // Manda sobre el espacio aunque no esté apuntado en él: enseñarle
            // "VIEWER" mientras puede hacerlo todo confundiría a cualquiera.
            return Role.ADMIN;
        }
        return miembros.findByUserIdAndWorkspaceId(user.getId(), user.getWorkspace().getId())
                .map(WorkspaceMember::getRole)
                .orElse(Role.VIEWER);
    }

    public boolean puede(User user, Permission permiso) {
        return permisosDe(user).contains(permiso);
    }

    /**
     * Deja pasar, o corta con 403.
     *
     * <p>Es lo que se llama al principio de cada acción que cambia algo. Un
     * 403 y no un 404: aquí la persona SÍ tiene acceso al workspace, así que
     * no se está revelando nada que no supiera; lo que no tiene es ese
     * permiso, y decírselo claro es lo que le permite pedirlo.
     */
    public void exigir(User user, Permission permiso) {
        if (!puede(user, permiso)) {
            throw new ForbiddenException(mensajeDe(permiso));
        }
    }

    /**
     * El porqué, en palabras de quien lo lee. Un "403 Forbidden" pelado deja a
     * la persona sin saber si le falta un permiso o si el sistema se rompió.
     */
    private static String mensajeDe(Permission permiso) {
        return switch (permiso) {
            case POST_CREATE -> "No tienes permiso para crear publicaciones en este espacio.";
            case POST_PUBLISH -> "No tienes permiso para publicar en este espacio.";
            case POST_SCHEDULE -> "No tienes permiso para programar publicaciones en este espacio.";
            case POST_DELETE -> "No tienes permiso para borrar publicaciones en este espacio.";
            case MEDIA_DELETE -> "No tienes permiso para borrar contenido en este espacio.";
            case NETWORK_MANAGE -> "No tienes permiso para administrar las redes de este espacio.";
            case MEMBER_MANAGE -> "No tienes permiso para administrar el equipo de este espacio.";
            case WORKSPACE_EDIT -> "No tienes permiso para cambiar los datos de este espacio.";
            case AI_USE -> "No tienes permiso para usar el redactor de IA en este espacio.";
        };
    }
}
