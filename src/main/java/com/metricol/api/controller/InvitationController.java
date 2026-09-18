package com.metricol.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.Invitation;
import com.metricol.api.entity.InvitationWorkspace;
import com.metricol.api.entity.Workspace;
import com.metricol.api.models.request.AceptarInvitacionRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.AuthResponse;
import com.metricol.api.models.response.InvitacionPreviaResponse;
import com.metricol.api.service.InvitationAcceptanceService;
import com.metricol.api.service.InvitationService;

import jakarta.validation.Valid;

/**
 * Aceptar una invitación. Sin sesión: quien llega todavía no tiene cuenta, o
 * la tiene pero no ha entrado.
 *
 * <p>Va aparte de {@code TeamController} justo por eso: aquel exige
 * administrar la organización, y este tiene que ser público.
 */
@RestController
@RequestMapping("/api/v1/invitaciones")
public class InvitationController {

    private final InvitationService invitaciones;
    private final InvitationAcceptanceService aceptacion;

    public InvitationController(InvitationService invitaciones, InvitationAcceptanceService aceptacion) {
        this.invitaciones = invitaciones;
        this.aceptacion = aceptacion;
    }

    /**
     * Qué dice la invitación, para poder enseñarla antes de pedir nada.
     *
     * <p>Quien abre el enlace tiene que ver quién lo invitó y a qué antes de
     * escribir una contraseña. Devuelve también si ese correo ya tiene cuenta,
     * que es lo que decide si la pantalla pide "crea tu contraseña" o "entra
     * con la tuya".
     */
    @GetMapping
    public ResponseEntity<ApiResponse<InvitacionPreviaResponse>> ver(@RequestParam String token) {
        Invitation invitacion = invitaciones.porToken(token);

        return ResponseEntity.ok(ApiResponse.success(new InvitacionPreviaResponse(
                invitacion.getEmail(),
                invitacion.getOrganization().getName(),
                invitacion.getInvitedBy() == null ? null : invitacion.getInvitedBy().getName(),
                invitacion.getWorkspaces().stream()
                        .map(InvitationWorkspace::getWorkspace)
                        .map(Workspace::getName)
                        .toList(),
                aceptacion.yaTieneCuenta(invitacion.getEmail()))));
    }

    /**
     * Entra a la organización y devuelve la sesión, lista para usar.
     *
     * <p>El token solo no basta: hay que probar quién eres, creando la cuenta
     * con ese correo o entrando con su contraseña. Un enlace reenviado no
     * puede dar acceso a la cuenta de otro.
     */
    @PostMapping("/aceptar")
    public ResponseEntity<ApiResponse<AuthResponse>> aceptar(@Valid @RequestBody AceptarInvitacionRequest peticion) {
        return ResponseEntity.ok(ApiResponse.success(aceptacion.aceptar(peticion)));
    }
}
