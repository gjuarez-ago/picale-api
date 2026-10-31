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
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.media.subtitulos.SubtitulosAutomaticos;

/** Subtítulos automáticos de la cuenta: si se ponen solos y con qué estilo. */
@RestController
@RequestMapping("/api/v1/subtitulos/ajustes")
public class SubtitulosAjustesController {

    private final SubtitulosAutomaticos automaticos;
    private final PermissionService permisos;

    public SubtitulosAjustesController(SubtitulosAutomaticos automaticos, PermissionService permisos) {
        this.automaticos = automaticos;
        this.permisos = permisos;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<SubtitulosAutomaticos.Ajustes>> ver(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(automaticos.ajustes(currentUser.getWorkspace().getId())));
    }

    /** Lo cambia quien puede crear publicaciones: es una forma de trabajar el contenido. */
    @PutMapping
    public ResponseEntity<ApiResponse<SubtitulosAutomaticos.Ajustes>> guardar(
            @AuthenticationPrincipal User currentUser, @RequestBody SubtitulosAutomaticos.Ajustes ajustes) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(automaticos.guardar(currentUser.getWorkspace().getId(), ajustes)));
    }
}
