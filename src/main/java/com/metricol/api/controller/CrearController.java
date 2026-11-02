package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.agente.CreacionManual;
import com.metricol.api.service.brand.BrandAccess;

/**
 * «Crear publicación» sin preguntas: se manda lo que se eligió y Pícale lo
 * trabaja; la pantalla pregunta en qué va. Ver {@link CreacionManual}.
 */
@RestController
@RequestMapping("/api/v1/crear")
public class CrearController {

    /** Un video, o una o varias fotos (en el orden en que se eligieron). */
    public record Pedido(List<UUID> archivos) {
    }

    private final CreacionManual creacion;
    private final PermissionService permisos;
    private final BrandAccess marca;

    public CrearController(CreacionManual creacion, PermissionService permisos, BrandAccess marca) {
        this.creacion = creacion;
        this.permisos = permisos;
        this.marca = marca;
    }

    /** Vuelve de inmediato; lo que sale es una propuesta en Pendientes. */
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> crear(@AuthenticationPrincipal User currentUser,
            @RequestBody Pedido pedido) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        marca.exigirCompleta(currentUser);
        creacion.pedir(pedido.archivos(), currentUser.getWorkspace().getId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/estado")
    public ResponseEntity<ApiResponse<CreacionManual.Estado>> estado(@AuthenticationPrincipal User currentUser,
            @RequestParam List<UUID> archivos) {
        return ResponseEntity.ok(ApiResponse.success(creacion.estado(archivos, currentUser.getWorkspace().getId())));
    }
}
