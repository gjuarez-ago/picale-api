package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.request.SubtitulosRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.media.subtitulos.Frase;
import com.metricol.api.service.media.subtitulos.SubtitulosService;

import jakarta.validation.Valid;

/**
 * Subtítulos con estilo para un video de Contenido. El avance de la copia se
 * consulta en {@code GET /api/v1/media/{copia}/trim-status}, igual que un
 * recorte: para la cola de video son el mismo tipo de trabajo.
 */
@RestController
@RequestMapping("/api/v1/media/{id}/subtitulos")
public class SubtitulosController {

    private final SubtitulosService service;
    private final PermissionService permisos;

    public SubtitulosController(SubtitulosService service, PermissionService permisos) {
        this.service = service;
        this.permisos = permisos;
    }

    /** Lo que se dice en el video, por frases, para corregirlo antes de pintarlo. */
    @PostMapping("/texto")
    public ResponseEntity<ApiResponse<List<Frase>>> texto(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(service.texto(id, currentUser.getWorkspace().getId())));
    }

    /** Crea la copia con subtítulos; el original no se toca. */
    @PostMapping
    public ResponseEntity<ApiResponse<MediaAssetResponse>> pedir(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id,
            @Valid @RequestBody(required = false) SubtitulosRequest request) {
        permisos.exigir(currentUser, Permission.POST_CREATE);
        SubtitulosRequest r = request == null ? new SubtitulosRequest(null, null, null) : request;
        return ResponseEntity.ok(ApiResponse.success(service.pedir(
                id, currentUser.getWorkspace().getId(), r.estilo(), r.color(), r.frases())));
    }
}
