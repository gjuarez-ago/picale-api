package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.EtapaAgente;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.WorkspaceMembershipService;
import com.metricol.api.service.agente.AgenteService;

/**
 * El agente de la cuenta actual: su switch, su bandeja "Por aprobar", y lo que
 * dejó en observación o descartó.
 *
 * <p>Aprobar pide programar, porque es lo que hace: deja la publicación
 * programada. Descartar pide borrar. Decidir sobre una foto en observación
 * pide crear, porque lo que sigue es preparar un borrador.
 */
@RestController
@RequestMapping("/api/v1/agente")
public class AgenteController {

    private final AgenteService agente;
    private final PermissionService permisos;
    private final WorkspaceMembershipService membresias;

    public AgenteController(AgenteService agente, PermissionService permisos, WorkspaceMembershipService membresias) {
        this.agente = agente;
        this.permisos = permisos;
        this.membresias = membresias;
    }

    /**
     * Todas las cuentas de la persona con lo que espera en cada una: la vista
     * del community manager. Solo las suyas: sale de sus membresías.
     */
    @GetMapping("/cuentas")
    public ResponseEntity<ApiResponse<List<AgenteService.Cuenta>>> cuentas(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(agente.resumen(membresias.misWorkspaces(currentUser))));
    }

    public record Switch(boolean activo) {
    }

    public record Decision(boolean va) {
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AgenteService.Estado>> estado(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(agente.estado(ws(currentUser))));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<AgenteService.Estado>> encender(
            @AuthenticationPrincipal User currentUser, @RequestBody Switch pedido) {
        permisos.exigir(currentUser, Permission.WORKSPACE_EDIT);
        return ResponseEntity.ok(ApiResponse.success(agente.encender(ws(currentUser), pedido.activo())));
    }

    public record HorarioPedido(List<Integer> dias, int desde, int hasta) {
    }

    @PutMapping("/horario")
    public ResponseEntity<ApiResponse<AgenteService.Estado>> horario(
            @AuthenticationPrincipal User currentUser, @RequestBody HorarioPedido pedido) {
        permisos.exigir(currentUser, Permission.WORKSPACE_EDIT);
        return ResponseEntity.ok(ApiResponse.success(
                agente.guardarHorario(ws(currentUser), pedido.dias(), pedido.desde(), pedido.hasta())));
    }

    /** Pausa de emergencia: apaga el agente y devuelve a "Por aprobar" lo que había programado. */
    @PostMapping("/pausar")
    public ResponseEntity<ApiResponse<AgenteService.Estado>> pausar(@AuthenticationPrincipal User currentUser) {
        permisos.exigir(currentUser, Permission.POST_SCHEDULE);
        agente.pausar(ws(currentUser));
        return ResponseEntity.ok(ApiResponse.success(agente.estado(ws(currentUser))));
    }

    @GetMapping("/propuestas")
    public ResponseEntity<ApiResponse<List<PostResponse>>> propuestas() {
        return ResponseEntity.ok(ApiResponse.success(agente.propuestas()));
    }

    @PostMapping("/propuestas/{id}/aprobar")
    public ResponseEntity<ApiResponse<PostResponse>> aprobar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.POST_SCHEDULE);
        return ResponseEntity.ok(ApiResponse.success(agente.aprobar(id, ws(currentUser))));
    }

    @PostMapping("/propuestas/aprobar-todas")
    public ResponseEntity<ApiResponse<AgenteService.Lote>> aprobarTodas(@AuthenticationPrincipal User currentUser) {
        permisos.exigir(currentUser, Permission.POST_SCHEDULE);
        return ResponseEntity.ok(ApiResponse.success(agente.aprobarTodas(ws(currentUser))));
    }

    @PostMapping("/propuestas/{id}/descartar")
    public ResponseEntity<ApiResponse<Void>> descartar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.POST_DELETE);
        agente.descartar(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** {@code etapa}: OBSERVACION o DESCARTADA. */
    @GetMapping("/archivos")
    public ResponseEntity<ApiResponse<List<MediaAssetResponse>>> archivos(@RequestParam EtapaAgente etapa) {
        return ResponseEntity.ok(ApiResponse.success(agente.archivos(etapa)));
    }

    @PostMapping("/archivos/{id}/decidir")
    public ResponseEntity<ApiResponse<Void>> decidir(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id, @RequestBody Decision pedido) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        agente.decidir(id, pedido.va(), ws(currentUser));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private static UUID ws(User currentUser) {
        return currentUser.getWorkspace().getId();
    }
}
