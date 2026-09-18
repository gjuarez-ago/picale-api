package com.metricol.api.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.metricol.api.entity.Invitation;
import com.metricol.api.entity.Organization;
import com.metricol.api.entity.OrganizationMember;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.OrgPermission;
import com.metricol.api.enums.OrgRole;
import com.metricol.api.enums.Permission;
import com.metricol.api.enums.Role;
import com.metricol.api.exception.ForbiddenException;
import com.metricol.api.models.request.InvitacionRequest;
import com.metricol.api.repository.InvitationRepository;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.UserRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.auth.EmailService;

/**
 * El candado de las invitaciones: quien puede invitar sin administrar la
 * organización solo da lo que él mismo tiene.
 *
 * <p>Es la regla que impide escalar privilegios. Si falla, un miembro con
 * "invitar" se invita a sí mismo con otro correo como administrador, o se
 * nombra administrador de un cliente donde solo era editor.
 */
class InvitationCandadoTest {

    private final InvitationRepository invitaciones = mock(InvitationRepository.class);
    private final OrganizationMemberRepository orgMiembros = mock(OrganizationMemberRepository.class);
    private final WorkspaceMemberRepository miembros = mock(WorkspaceMemberRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final UserRepository usuarios = mock(UserRepository.class);
    private final OrganizationService organizaciones = mock(OrganizationService.class);
    private final EmailService correo = mock(EmailService.class);
    private final PermissionService permisos = mock(PermissionService.class);

    private InvitationService servicio;

    private final Organization agencia = Organization.builder().id(UUID.randomUUID()).name("Agencia").build();
    private final Workspace cliente = Workspace.builder().id(UUID.randomUUID()).name("Tacos").organization(agencia).build();
    private final User subordinado = User.builder().id(UUID.randomUUID()).name("Ana").build();

    @BeforeEach
    void preparar() {
        servicio = new InvitationService(invitaciones, orgMiembros, miembros, workspaces, usuarios,
                organizaciones, correo, permisos, "https://picale.click");

        when(organizaciones.deLaSesion(subordinado)).thenReturn(agencia);
        // Un MIEMBRO con permiso de invitar, no un administrador.
        OrganizationMember ana = OrganizationMember.builder()
                .user(subordinado).organization(agencia).role(OrgRole.MEMBER)
                .permissions(EnumSet.of(OrgPermission.INVITE_MEMBERS)).build();
        when(organizaciones.exigirPermiso(subordinado, agencia.getId(), OrgPermission.INVITE_MEMBERS)).thenReturn(ana);

        when(workspaces.findById(cliente.getId())).thenReturn(Optional.of(cliente));
        when(usuarios.findByEmail(anyString())).thenReturn(Optional.empty());
        when(invitaciones.findVigentePara(any(), anyString())).thenReturn(Optional.empty());
        when(invitaciones.save(any(Invitation.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("Un miembro no puede invitar a un administrador de la organización")
    void noInvitaAdministradores() {
        InvitacionRequest peticion = invitacion(OrgRole.ADMIN, Role.EDITOR);

        assertThatThrownBy(() -> servicio.invitar(subordinado, peticion))
                .isInstanceOf(ForbiddenException.class);
        verify(correo, never()).enviarInvitacion(anyString(), any(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Nadie da el papel de dueño por invitación")
    void nadieInvitaDuenios() {
        assertThatThrownBy(() -> servicio.invitar(subordinado, invitacion(OrgRole.OWNER, Role.EDITOR)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("No puede meter a nadie a un cliente donde él no entra")
    void soloASusClientes() {
        when(permisos.permisosDe(subordinado.getId(), cliente.getId())).thenReturn(EnumSet.noneOf(Permission.class));

        assertThatThrownBy(() -> servicio.invitar(subordinado, invitacion(OrgRole.MEMBER, Role.VIEWER)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("Un editor no puede nombrar administrador del cliente a nadie")
    void noDaMasDeLoQueTiene() {
        when(permisos.permisosDe(subordinado.getId(), cliente.getId()))
                .thenReturn(EnumSet.copyOf(Role.EDITOR.permisosPorDefecto()));

        assertThatThrownBy(() -> servicio.invitar(subordinado, invitacion(OrgRole.MEMBER, Role.ADMIN)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("Tampoco colando un permiso suelto que él no tiene")
    void noColaPermisosSueltos() {
        when(permisos.permisosDe(subordinado.getId(), cliente.getId()))
                .thenReturn(EnumSet.copyOf(Role.EDITOR.permisosPorDefecto()));

        InvitacionRequest peticion = invitacion(OrgRole.MEMBER, Role.EDITOR);
        peticion.getWorkspaces().get(0).setExtraPermissions(EnumSet.of(Permission.NETWORK_MANAGE));

        assertThatThrownBy(() -> servicio.invitar(subordinado, peticion))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("Lo que sí tiene lo puede dar: un editor invita a otro editor")
    void loQueTieneLoPuedeDar() {
        when(permisos.permisosDe(subordinado.getId(), cliente.getId()))
                .thenReturn(EnumSet.copyOf(Role.EDITOR.permisosPorDefecto()));

        assertThatCode(() -> servicio.invitar(subordinado, invitacion(OrgRole.MEMBER, Role.EDITOR)))
                .doesNotThrowAnyException();
        verify(correo).enviarInvitacion(eq("nuevo@agencia.com"), any(), any(), anyString(), anyInt());
    }

    private InvitacionRequest invitacion(OrgRole papel, Role rolEnCliente) {
        InvitacionRequest.EspacioAsignado asignado = new InvitacionRequest.EspacioAsignado();
        asignado.setWorkspaceId(cliente.getId());
        asignado.setRole(rolEnCliente);

        InvitacionRequest peticion = new InvitacionRequest();
        peticion.setEmail("nuevo@agencia.com");
        peticion.setOrgRole(papel);
        peticion.setWorkspaces(new java.util.ArrayList<>(List.of(asignado)));
        return peticion;
    }
}
