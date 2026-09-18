package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.Invitation;
import com.metricol.api.entity.InvitationWorkspace;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.OrgRole;
import com.metricol.api.models.request.AccesoRequest;
import com.metricol.api.models.request.InvitacionRequest;
import com.metricol.api.models.request.PapelRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.InvitacionResponse;
import com.metricol.api.models.response.MiembroResponse;
import com.metricol.api.service.InvitationService;
import com.metricol.api.service.TeamService;

import jakarta.validation.Valid;

/**
 * El equipo de la organización: quién está, a qué entra, y a quién se invita.
 *
 * <p>Todo esto exige administrar la organización, y lo comprueban los
 * servicios. Es la pantalla que reparte el acceso a las cuentas de los
 * clientes: quien solo trabaja dentro de una no la abre.
 */
@RestController
@RequestMapping("/api/v1/equipo")
public class TeamController {

    private final TeamService equipo;
    private final InvitationService invitaciones;

    public TeamController(TeamService equipo, InvitationService invitaciones) {
        this.equipo = equipo;
        this.invitaciones = invitaciones;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MiembroResponse>>> miembros(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(equipo.equipo(currentUser)));
    }

    /** Cambia a qué espacios entra alguien y qué puede hacer en cada uno. */
    @PutMapping("/{userId}/accesos")
    public ResponseEntity<ApiResponse<MiembroResponse>> accesos(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID userId,
            @RequestBody List<AccesoRequest> accesos) {
        return ResponseEntity.ok(ApiResponse.success(equipo.cambiarAccesos(currentUser, userId, accesos)));
    }

    /** Lo sube a administrador de la organización, o lo baja a miembro. */
    @PutMapping("/{userId}/papel")
    public ResponseEntity<ApiResponse<MiembroResponse>> papel(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID userId,
            @RequestBody PapelRequest peticion) {
        return ResponseEntity.ok(ApiResponse.success(equipo.cambiarPapel(currentUser, userId, peticion.getOrgRole())));
    }

    /** Invitar gente y crear espacios, para un miembro que no administra. */
    @PutMapping("/{userId}/organizacion")
    public ResponseEntity<ApiResponse<MiembroResponse>> permisosDeOrganizacion(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID userId,
            @RequestBody java.util.Set<com.metricol.api.enums.OrgPermission> permisos) {
        return ResponseEntity.ok(ApiResponse.success(
                equipo.cambiarPermisosDeOrganizacion(currentUser, userId, permisos)));
    }

    /** Lo saca de la organización y de todos sus espacios. */
    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<Void>> quitar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID userId) {
        equipo.quitar(currentUser, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ----------------------------------------------------------- Invitaciones

    @GetMapping("/invitaciones")
    public ResponseEntity<ApiResponse<List<InvitacionResponse>>> pendientes(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                invitaciones.pendientes(currentUser).stream().map(TeamController::respuesta).toList()));
    }

    @PostMapping("/invitaciones")
    public ResponseEntity<ApiResponse<InvitacionResponse>> invitar(
            @AuthenticationPrincipal User currentUser, @Valid @RequestBody InvitacionRequest peticion) {
        return ResponseEntity.ok(ApiResponse.success(respuesta(invitaciones.invitar(currentUser, peticion))));
    }

    /** El enlace deja de servir aunque ya esté en el correo de alguien. */
    @DeleteMapping("/invitaciones/{id}")
    public ResponseEntity<ApiResponse<Void>> revocar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        invitaciones.revocar(currentUser, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private static InvitacionResponse respuesta(Invitation invitacion) {
        return new InvitacionResponse(
                invitacion.getId(),
                invitacion.getEmail(),
                invitacion.getOrgRole() == null ? OrgRole.MEMBER : invitacion.getOrgRole(),
                invitacion.getCreatedAt(),
                invitacion.getExpiresAt(),
                invitacion.getInvitedBy() == null ? null : invitacion.getInvitedBy().getName(),
                invitacion.getWorkspaces().stream()
                        .map(InvitationWorkspace::getWorkspace)
                        .map(Workspace::getName)
                        .toList());
    }
}
