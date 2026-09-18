package com.metricol.api.service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Organization;
import com.metricol.api.entity.OrganizationMember;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.OrgPermission;
import com.metricol.api.enums.OrgRole;
import com.metricol.api.enums.Permission;
import com.metricol.api.enums.Role;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.AccesoRequest;
import com.metricol.api.models.response.MiembroResponse;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * El equipo de la organización: quién está, a qué entra y qué puede hacer.
 *
 * <p>Todo lo de aquí exige administrar la organización. Es la pantalla donde se
 * reparte el acceso a las cuentas de los clientes, así que no la abre quien
 * solo trabaja dentro de una.
 */
@Service
public class TeamService {

    private final OrganizationMemberRepository orgMiembros;
    private final WorkspaceMemberRepository miembros;
    private final WorkspaceRepository workspaces;
    private final UserRepository usuarios;
    private final OrganizationService organizaciones;

    public TeamService(OrganizationMemberRepository orgMiembros, WorkspaceMemberRepository miembros,
            WorkspaceRepository workspaces, UserRepository usuarios, OrganizationService organizaciones) {
        this.orgMiembros = orgMiembros;
        this.miembros = miembros;
        this.workspaces = workspaces;
        this.usuarios = usuarios;
        this.organizaciones = organizaciones;
    }

    /** Todo el equipo, con el acceso de cada quien a cada espacio. */
    @Transactional(readOnly = true)
    public List<MiembroResponse> equipo(User actual) {
        Organization organizacion = organizaciones.deLaSesion(actual);
        organizaciones.exigirAdministrador(actual, organizacion.getId());

        List<Workspace> espacios = workspaces.findDeLaOrganizacion(organizacion.getId());

        return orgMiembros.findDeLaOrganizacion(organizacion.getId()).stream()
                .map(m -> respuestaDe(m, espacios, actual))
                .sorted(Comparator.comparing(MiembroResponse::orgRole).thenComparing(MiembroResponse::name))
                .toList();
    }

    /**
     * Cambia a qué espacios entra alguien y qué puede hacer en cada uno.
     *
     * <p>Se manda la lista completa, no un cambio suelto: la pantalla enseña
     * todos los espacios con sus casillas, y mandar el estado final evita el
     * problema clásico de dos administradores tocando a la misma persona a la
     * vez y quedándose cada uno con la mitad de lo que hizo el otro.
     */
    @Transactional
    public MiembroResponse cambiarAccesos(User actual, UUID userId, List<AccesoRequest> accesos) {
        Organization organizacion = organizaciones.deLaSesion(actual);
        organizaciones.exigirAdministrador(actual, organizacion.getId());

        OrganizationMember miembro = orgMiembros.findByUserIdAndOrganizationId(userId, organizacion.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Esa persona no está en tu organización."));

        List<Workspace> espacios = workspaces.findDeLaOrganizacion(organizacion.getId());
        Set<UUID> deLaOrganizacion = espacios.stream().map(Workspace::getId).collect(java.util.stream.Collectors.toSet());

        for (AccesoRequest acceso : accesos == null ? List.<AccesoRequest>of() : accesos) {
            // Un id que no es de esta organización no se ignora en silencio: si
            // llegó, alguien está intentando dar acceso al cliente de otra
            // agencia, y eso tiene que fallar ruidosamente.
            if (!deLaOrganizacion.contains(acceso.getWorkspaceId())) {
                throw new ResourceNotFoundException("Espacio de trabajo no encontrado.");
            }

            WorkspaceMember actualEnEspacio = miembros
                    .findByUserIdAndWorkspaceId(userId, acceso.getWorkspaceId()).orElse(null);

            if (!acceso.isTieneAcceso()) {
                if (actualEnEspacio != null) {
                    miembros.delete(actualEnEspacio);
                }
                continue;
            }

            Workspace espacio = espacios.stream()
                    .filter(w -> w.getId().equals(acceso.getWorkspaceId()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Espacio de trabajo no encontrado."));

            WorkspaceMember fila = actualEnEspacio != null ? actualEnEspacio : WorkspaceMember.builder()
                    .user(usuarios.findById(userId)
                            .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado.")))
                    .workspace(espacio)
                    .build();

            fila.setRole(acceso.rolONormal());
            fila.setExtraPermissions(acceso.extras());
            fila.setDeniedPermissions(acceso.negados());
            miembros.save(fila);
        }

        return respuestaDe(miembro, espacios, actual);
    }

    /**
     * Cambia el papel de alguien en la organización.
     *
     * <p>Con una red de seguridad: no se puede quitar al último dueño. Una
     * organización sin dueño no la recupera nadie desde la aplicación, y el
     * error se comete justo cuando alguien intenta "ordenar" los papeles.
     */
    @Transactional
    public MiembroResponse cambiarPapel(User actual, UUID userId, OrgRole papel) {
        Organization organizacion = organizaciones.deLaSesion(actual);
        organizaciones.exigirAdministrador(actual, organizacion.getId());

        OrganizationMember miembro = orgMiembros.findByUserIdAndOrganizationId(userId, organizacion.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Esa persona no está en tu organización."));

        // Lo de dueño lo toca solo un dueño. Sin esto, un administrador podía
        // nombrarse dueño a sí mismo —o bajar al dueño— mandando la petición
        // a mano, y quedarse con una organización que no es suya.
        if (papel == OrgRole.OWNER || miembro.getRole() == OrgRole.OWNER) {
            exigirDueño(actual, organizacion.getId());
        }

        if (miembro.getRole() == OrgRole.OWNER && papel != OrgRole.OWNER
                && orgMiembros.countByOrganizationIdAndRole(organizacion.getId(), OrgRole.OWNER) <= 1) {
            throw new IllegalStateException("Es el único dueño de la organización. Nombra a otro antes de cambiarle el papel.");
        }

        miembro.setRole(papel == null ? OrgRole.MEMBER : papel);
        orgMiembros.save(miembro);

        return respuestaDe(miembro, workspaces.findDeLaOrganizacion(organizacion.getId()), actual);
    }

    /**
     * Le da o le quita a un miembro lo que puede hacer en la organización:
     * invitar gente y crear espacios.
     *
     * <p>Solo quien administra. Y no tiene sentido para un administrador,
     * que ya lo tiene todo por su papel: tocarlo ahí no cambiaría nada, así que
     * se rechaza en vez de guardar algo que no significa nada.
     */
    @Transactional
    public MiembroResponse cambiarPermisosDeOrganizacion(User actual, UUID userId, Set<OrgPermission> permisos) {
        Organization organizacion = organizaciones.deLaSesion(actual);
        organizaciones.exigirAdministrador(actual, organizacion.getId());

        OrganizationMember miembro = orgMiembros.findByUserIdAndOrganizationId(userId, organizacion.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Esa persona no está en tu organización."));

        if (miembro.getRole().administraLaOrganizacion()) {
            throw new IllegalStateException("Quien administra la organización ya puede todo esto por su papel.");
        }

        miembro.setPermissions(permisos == null ? EnumSet.noneOf(OrgPermission.class) : EnumSet.copyOf(permisos));
        orgMiembros.save(miembro);

        return respuestaDe(miembro, workspaces.findDeLaOrganizacion(organizacion.getId()), actual);
    }

    /**
     * Saca a alguien de la organización y de todos sus espacios.
     *
     * <p>Sus publicaciones se quedan: son del cliente, no suyas. Lo que se va
     * es su acceso.
     */
    @Transactional
    public void quitar(User actual, UUID userId) {
        Organization organizacion = organizaciones.deLaSesion(actual);
        organizaciones.exigirAdministrador(actual, organizacion.getId());

        if (userId.equals(actual.getId())) {
            throw new IllegalStateException("No puedes quitarte a ti mismo. Pídeselo a otro administrador.");
        }

        OrganizationMember miembro = orgMiembros.findByUserIdAndOrganizationId(userId, organizacion.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Esa persona no está en tu organización."));

        // A un dueño solo lo saca otro dueño, por lo mismo que en cambiarPapel.
        if (miembro.getRole() == OrgRole.OWNER) {
            exigirDueño(actual, organizacion.getId());
        }

        if (miembro.getRole() == OrgRole.OWNER
                && orgMiembros.countByOrganizationIdAndRole(organizacion.getId(), OrgRole.OWNER) <= 1) {
            throw new IllegalStateException("Es el único dueño de la organización. Nombra a otro antes de quitarlo.");
        }

        for (Workspace espacio : workspaces.findDeLaOrganizacion(organizacion.getId())) {
            miembros.findByUserIdAndWorkspaceId(userId, espacio.getId()).ifPresent(miembros::delete);
        }
        orgMiembros.delete(miembro);
    }

    private void exigirDueño(User actual, UUID organizationId) {
        boolean soyDueño = orgMiembros.findByUserIdAndOrganizationId(actual.getId(), organizationId)
                .map(m -> m.getRole() == OrgRole.OWNER)
                .orElse(false);
        if (!soyDueño) {
            throw new com.metricol.api.exception.ForbiddenException(
                    "Solo el dueño de la organización puede cambiar quién es dueño.");
        }
    }

    private MiembroResponse respuestaDe(OrganizationMember miembro, List<Workspace> espacios, User actual) {
        User persona = miembro.getUser();

        List<MiembroResponse.AccesoResponse> accesos = miembro.getRole().administraLaOrganizacion()
                ? List.of()
                : espacios.stream()
                        .map(espacio -> miembros.findByUserIdAndWorkspaceId(persona.getId(), espacio.getId())
                                .map(fila -> new MiembroResponse.AccesoResponse(
                                        espacio.getId(),
                                        espacio.getName(),
                                        fila.getRole().name(),
                                        nombres(fila.permisosEfectivos()),
                                        nombres(fila.getExtraPermissions()),
                                        nombres(fila.getDeniedPermissions())))
                                .orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList();

        List<String> deOrganizacion = java.util.Arrays.stream(OrgPermission.values())
                .filter(miembro::puede)
                .map(Enum::name)
                .toList();

        return new MiembroResponse(persona.getId(), persona.getName(), persona.getEmail(), miembro.getRole(),
                persona.getId().equals(actual.getId()), deOrganizacion, accesos);
    }

    private static List<String> nombres(Set<Permission> permisos) {
        return (permisos == null ? EnumSet.noneOf(Permission.class) : permisos).stream()
                .map(Enum::name)
                .sorted()
                .toList();
    }

    /** Los roles y permisos que existen, para que la pantalla no los invente. */
    public static List<String> rolesDeEspacio() {
        return List.of(Role.ADMIN.name(), Role.EDITOR.name(), Role.VIEWER.name());
    }
}
