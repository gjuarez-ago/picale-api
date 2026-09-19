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
import org.springframework.web.multipart.MultipartFile;

import com.metricol.api.entity.User;
import com.metricol.api.models.request.EspacioUpdateRequest;
import com.metricol.api.models.request.WorkspaceCreateRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.AuthResponse;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.models.response.MiWorkspaceResponse;
import com.metricol.api.service.WorkspaceMembershipService;

import jakarta.validation.Valid;

/**
 * Los workspaces de un usuario: listar, crear y cambiar de uno a otro.
 *
 * <p>En plural y aparte de {@code /api/v1/workspace}, que sigue siendo "el
 * workspace activo" y que la app ya usa: así nada de lo existente cambia de
 * significado.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspacesController {

    private final WorkspaceMembershipService membresias;

    public WorkspacesController(WorkspaceMembershipService membresias) {
        this.membresias = membresias;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MiWorkspaceResponse>>> mios(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(membresias.misWorkspaces(currentUser)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MiWorkspaceResponse>> crear(
            @AuthenticationPrincipal User currentUser, @Valid @RequestBody WorkspaceCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(membresias.crear(currentUser, request)));
    }

    /**
     * Cambia al workspace indicado. Devuelve la sesión completa —token nuevo
     * incluido— para que la app la guarde igual que al entrar.
     */
    @PostMapping("/{id}/activar")
    public ResponseEntity<ApiResponse<AuthResponse>> activar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(membresias.activar(currentUser, id)));
    }

    /** Nombre, logotipo, color y etiquetas. Solo quien administra la organización. */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<MiWorkspaceResponse>> editar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id,
            @RequestBody EspacioUpdateRequest peticion) {
        return ResponseEntity.ok(ApiResponse.success(membresias.editar(currentUser, id, peticion)));
    }

    /**
     * Sube el logotipo de {@code id}, sea o no el espacio activo de quien lo
     * sube. Devuelve la URL para que la pantalla la mande luego en
     * {@link #editar}; subir no guarda el logotipo por sí solo.
     *
     * <p>Aparte de {@code /media/upload}: ese guarda el archivo a nombre del
     * espacio activo de la sesión, y el modal de administrar espacios deja
     * tocar cualquiera de la organización sin cambiar a él primero.
     */
    @PostMapping("/{id}/logo")
    public ResponseEntity<ApiResponse<MediaAssetResponse>> subirLogo(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.success(membresias.subirLogo(currentUser, id, file)));
    }

    /**
     * Archiva un espacio: deja de publicar y sale de la lista, sin borrar nada.
     *
     * <p>Es un POST y no un DELETE a propósito: no se borra el espacio, y
     * llamarlo DELETE haría creer —a quien lea el código o la red— que sí.
     */
    @PostMapping("/{id}/archivar")
    public ResponseEntity<ApiResponse<MiWorkspaceResponse>> archivar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(membresias.archivar(currentUser, id, true)));
    }

    /** Lo devuelve a la vida: vuelve a aparecer y vuelve a publicar. */
    @PostMapping("/{id}/restaurar")
    public ResponseEntity<ApiResponse<MiWorkspaceResponse>> restaurar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(membresias.archivar(currentUser, id, false)));
    }
}
