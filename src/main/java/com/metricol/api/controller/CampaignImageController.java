package com.metricol.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.CampaignImageResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.campaign.CampaignImageService;

import jakarta.validation.Valid;

/**
 * Las imágenes de campaña de la app móvil. Ver {@link CampaignImageService}.
 *
 * <p>La petición es síncrona: la app espera hasta tres minutos y Cloudflare
 * corta a los 100 segundos. Si una generación deja de caber ahí, el siguiente
 * paso es un trabajo con estado consultable, no más tiempo de espera.
 */
@RestController
@RequestMapping("/api/v1/campaign-images")
public class CampaignImageController {

    private final CampaignImageService service;
    private final PermissionService permisos;

    public CampaignImageController(CampaignImageService service, PermissionService permisos) {
        this.service = service;
        this.permisos = permisos;
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<CampaignImageResponse>> generar(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody CampaignImageRequest request) {
        // Crear la imagen es el primer paso de crear una publicación.
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(service.generar(currentUser, request)));
    }
}
