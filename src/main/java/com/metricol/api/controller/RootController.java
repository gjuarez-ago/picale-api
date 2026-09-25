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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.models.request.root.AdministradorRequest;
import com.metricol.api.models.request.root.AjusteDeCreditosRequest;
import com.metricol.api.models.request.root.CupoRequest;
import com.metricol.api.models.request.root.LicenciaRequest;
import com.metricol.api.models.request.root.SinLimitesRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.root.AdministradorResponse;
import com.metricol.api.models.response.root.OrganizacionDetalleResponse;
import com.metricol.api.models.response.root.OrganizacionResumenResponse;
import com.metricol.api.service.root.AdministradoresService;
import com.metricol.api.service.root.RootAccessService;
import com.metricol.api.service.root.RootAdminService;

import jakarta.validation.Valid;

/**
 * La administración de la plataforma: todas las organizaciones, sus espacios,
 * quién entra a cada uno, y sus licencias y créditos.
 *
 * <p>Solo para quien administra la plataforma ({@code User.platformAdmin}),
 * y con sesión normal: no es un endpoint de operación con llave
 * ({@code /ops/**}), es una pantalla de la web. Cada método pasa primero por
 * {@link RootAccessService}; el resto de la API no cambia.
 *
 * <p>Los cambios devuelven la organización completa ya actualizada, para que
 * la pantalla no tenga que volver a pedirla.
 */
@RestController
@RequestMapping("/api/v1/root")
public class RootController {

    private final RootAccessService acceso;
    private final RootAdminService admin;
    private final AdministradoresService administradores;

    public RootController(RootAccessService acceso, RootAdminService admin, AdministradoresService administradores) {
        this.acceso = acceso;
        this.admin = admin;
        this.administradores = administradores;
    }

    @GetMapping("/organizaciones")
    public ResponseEntity<ApiResponse<List<OrganizacionResumenResponse>>> organizaciones(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(required = false) String q) {
        acceso.exigir(currentUser);
        return ResponseEntity.ok(ApiResponse.success(admin.listar(q)));
    }

    @GetMapping("/organizaciones/{id}")
    public ResponseEntity<ApiResponse<OrganizacionDetalleResponse>> organizacion(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        acceso.exigir(currentUser);
        return ResponseEntity.ok(ApiResponse.success(admin.detalle(id)));
    }

    /** Exentar de pago (como la cuenta de la casa), o dejar de hacerlo. */
    @PutMapping("/organizaciones/{id}/sin-limites")
    public ResponseEntity<ApiResponse<OrganizacionDetalleResponse>> sinLimites(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id,
            @Valid @RequestBody SinLimitesRequest request) {
        acceso.exigir(currentUser);
        return ResponseEntity.ok(ApiResponse.success(admin.cambiarSinLimites(id, request.getValor(), currentUser)));
    }

    @PutMapping("/organizaciones/{id}/cupo")
    public ResponseEntity<ApiResponse<OrganizacionDetalleResponse>> cupo(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id,
            @Valid @RequestBody CupoRequest request) {
        acceso.exigir(currentUser);
        return ResponseEntity.ok(ApiResponse.success(admin.cambiarCupo(id, request.getMaxWorkspaces(), currentUser)));
    }

    @PutMapping("/espacios/{workspaceId}/licencia")
    public ResponseEntity<ApiResponse<OrganizacionDetalleResponse>> licencia(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID workspaceId,
            @Valid @RequestBody LicenciaRequest request) {
        acceso.exigir(currentUser);
        return ResponseEntity.ok(ApiResponse.success(admin.fijarLicencia(workspaceId, request, currentUser)));
    }

    @PostMapping("/espacios/{workspaceId}/creditos")
    public ResponseEntity<ApiResponse<OrganizacionDetalleResponse>> creditos(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID workspaceId,
            @Valid @RequestBody AjusteDeCreditosRequest request) {
        acceso.exigir(currentUser);
        return ResponseEntity.ok(ApiResponse.success(admin.ajustarCreditos(workspaceId, request, currentUser)));
    }

    /** Quién administra la plataforma. */
    @GetMapping("/administradores")
    public ResponseEntity<ApiResponse<List<AdministradorResponse>>> administradores(
            @AuthenticationPrincipal User currentUser) {
        acceso.exigir(currentUser);
        return ResponseEntity.ok(ApiResponse.success(administradores.listar(currentUser)));
    }

    /** Darle la administración de la plataforma a una cuenta que ya existe. */
    @PostMapping("/administradores")
    public ResponseEntity<ApiResponse<List<AdministradorResponse>>> agregarAdministrador(
            @AuthenticationPrincipal User currentUser, @Valid @RequestBody AdministradorRequest request) {
        acceso.exigir(currentUser);
        return ResponseEntity.ok(ApiResponse.success(administradores.agregar(request.getEmail(), currentUser)));
    }

    /** Quitársela. Ni a la raíz ni a uno mismo (409). */
    @DeleteMapping("/administradores/{userId}")
    public ResponseEntity<ApiResponse<List<AdministradorResponse>>> quitarAdministrador(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID userId) {
        acceso.exigir(currentUser);
        return ResponseEntity.ok(ApiResponse.success(administradores.quitar(userId, currentUser)));
    }
}
