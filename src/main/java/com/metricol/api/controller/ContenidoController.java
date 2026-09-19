package com.metricol.api.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.ContenidoEstadoResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.campaign.ContenidoJobs;

import jakarta.validation.Valid;

/**
 * Crear contenido con IA para varias redes a la vez, en segundo plano.
 *
 * <p>{@code POST /generate} valida y contesta 202 con un identificador en
 * segundos; {@code GET /jobs/{id}} dice cómo va y trae cada versión en cuanto
 * está lista. Ver {@link ContenidoJobs} para el porqué: una sola petición se
 * pasaría de los 100 segundos que aguanta Cloudflare.
 */
@RestController
@RequestMapping("/api/v1/content")
public class ContenidoController {

    private final ContenidoJobs trabajos;
    private final PermissionService permisos;

    public ContenidoController(ContenidoJobs trabajos, PermissionService permisos) {
        this.trabajos = trabajos;
        this.permisos = permisos;
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, String>>> generar(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody CampaignImageRequest request) {
        // Crear el contenido es el primer paso de crear una publicación.
        permisos.exigir(currentUser, Permission.POST_CREATE);
        String id = trabajos.iniciar(currentUser, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(Map.of("jobId", id)));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<ApiResponse<ContenidoEstadoResponse>> estado(
            @AuthenticationPrincipal User currentUser, @PathVariable String id) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(trabajos.estado(currentUser, id)));
    }
}
