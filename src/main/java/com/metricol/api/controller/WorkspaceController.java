package com.metricol.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.request.BrandRequest;
import com.metricol.api.models.request.WorkspaceUpdateRequest;
import com.metricol.api.models.response.BrandResponse;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.WorkspaceResponse;
import com.metricol.api.service.BrandService;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.WorkspaceService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {

    private final WorkspaceService service;
    private final BrandService marca;
    private final PermissionService permisos;

    public WorkspaceController(WorkspaceService service, BrandService marca, PermissionService permisos) {
        this.service = service;
        this.marca = marca;
        this.permisos = permisos;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<WorkspaceResponse>> get(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(service.get(currentUser)));
    }

    /** La marca del espacio (lo que la IA sabe del negocio) y qué tan completa está. */
    @GetMapping("/brand")
    public ResponseEntity<ApiResponse<BrandResponse>> brand(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(marca.obtener(currentUser)));
    }

    /** Guarda la marca. Quien puede editar el espacio. */
    @PutMapping("/brand")
    public ResponseEntity<ApiResponse<BrandResponse>> guardarMarca(
            @AuthenticationPrincipal User currentUser, @Valid @RequestBody BrandRequest request) {
        permisos.exigir(currentUser, Permission.WORKSPACE_EDIT);
        return ResponseEntity.ok(ApiResponse.success(marca.guardar(currentUser, request)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<WorkspaceResponse>> update(
            @AuthenticationPrincipal User currentUser, @Valid @RequestBody WorkspaceUpdateRequest request) {
        permisos.exigir(currentUser, Permission.WORKSPACE_EDIT);
        return ResponseEntity.ok(ApiResponse.success(service.update(currentUser, request)));
    }
}
