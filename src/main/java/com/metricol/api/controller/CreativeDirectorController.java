package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

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
import com.metricol.api.models.request.CreativeBatchRequest;
import com.metricol.api.models.request.CreativeProposalTextRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.CreativeBatchResponse;
import com.metricol.api.models.response.CreativeComposerDraftResponse;
import com.metricol.api.models.response.CreativeProposalResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.creative.CreativeDirectorService;
import com.metricol.api.service.brand.BrandAccess;

import jakarta.validation.Valid;

/** API mínima para la experiencia: subir/seleccionar, generar, revisar y aprobar. */
@RestController
@RequestMapping("/api/v1/creative-director")
public class CreativeDirectorController {
    private final CreativeDirectorService director;
    private final PermissionService permissions;
    private final BrandAccess marca;

    public CreativeDirectorController(CreativeDirectorService director, PermissionService permissions,
            BrandAccess marca) {
        this.director = director; this.permissions = permissions; this.marca = marca;
    }

    @PostMapping("/batches")
    public ResponseEntity<ApiResponse<CreativeBatchResponse>> create(@AuthenticationPrincipal User user,
            @Valid @RequestBody CreativeBatchRequest request) {
        permissions.exigir(user, Permission.POST_CREATE);
        marca.exigirCompleta(user);
        return ResponseEntity.accepted().body(ApiResponse.success(director.create(request, workspace(user))));
    }

    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<List<CreativeBatchResponse>>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(director.list(workspace(user))));
    }

    @GetMapping("/batches/{id}")
    public ResponseEntity<ApiResponse<CreativeBatchResponse>> get(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(director.get(id, workspace(user))));
    }

    @PostMapping("/proposals/{id}/approve")
    public ResponseEntity<ApiResponse<CreativeProposalResponse>> approve(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        permissions.exigir(user, Permission.POST_CREATE);
        marca.exigirCompleta(user);
        return ResponseEntity.ok(ApiResponse.success(director.approve(id, workspace(user), user.getId())));
    }

    @PostMapping("/proposals/{id}/text")
    public ResponseEntity<ApiResponse<CreativeProposalResponse>> editText(@AuthenticationPrincipal User user,
            @PathVariable UUID id, @Valid @RequestBody CreativeProposalTextRequest request) {
        permissions.exigir(user, Permission.POST_CREATE);
        marca.exigirCompleta(user);
        return ResponseEntity.ok(ApiResponse.success(director.editText(id, workspace(user), request)));
    }

    @PostMapping("/proposals/{id}/discard")
    public ResponseEntity<ApiResponse<CreativeProposalResponse>> discard(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        permissions.exigir(user, Permission.POST_CREATE);
        marca.exigirCompleta(user);
        return ResponseEntity.ok(ApiResponse.success(director.discard(id, workspace(user))));
    }

    @PostMapping("/proposals/{id}/regenerate")
    public ResponseEntity<ApiResponse<CreativeProposalResponse>> regenerate(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        permissions.exigir(user, Permission.POST_CREATE);
        marca.exigirCompleta(user);
        return ResponseEntity.accepted().body(ApiResponse.success(director.regenerate(id, workspace(user))));
    }

    @PostMapping("/batches/{id}/retry")
    public ResponseEntity<ApiResponse<CreativeBatchResponse>> retry(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        permissions.exigir(user, Permission.POST_CREATE);
        marca.exigirCompleta(user);
        return ResponseEntity.accepted().body(ApiResponse.success(director.retry(id, workspace(user))));
    }

    @GetMapping("/proposals/{id}/composer")
    public ResponseEntity<ApiResponse<CreativeComposerDraftResponse>> composer(@AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        permissions.exigir(user, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(director.composer(id, workspace(user))));
    }

    private static UUID workspace(User user) {
        if (user.getWorkspace() == null) throw new IllegalStateException("Elige un espacio de trabajo.");
        return user.getWorkspace().getId();
    }
}
