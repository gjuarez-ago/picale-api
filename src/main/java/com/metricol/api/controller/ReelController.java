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
import com.metricol.api.models.request.ReelCreateRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.ReelRenderResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.reels.ReelRenderService;
import com.metricol.api.service.brand.BrandAccess;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/reels")
public class ReelController {

    private final ReelRenderService reels;
    private final PermissionService permissions;
    private final BrandAccess marca;

    public ReelController(ReelRenderService reels, PermissionService permissions, BrandAccess marca) {
        this.reels = reels;
        this.permissions = permissions;
        this.marca = marca;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReelRenderResponse>> create(
            @AuthenticationPrincipal User user, @Valid @RequestBody ReelCreateRequest request) {
        permissions.exigir(user, Permission.POST_CREATE);
        marca.exigirCompleta(user);
        return ResponseEntity.accepted().body(ApiResponse.success(
                reels.create(request, user.getWorkspace().getId())));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ReelRenderResponse>>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(reels.list(user.getWorkspace().getId())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ReelRenderResponse>> get(
            @AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(reels.get(id, user.getWorkspace().getId())));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<ReelRenderResponse>> cancel(
            @AuthenticationPrincipal User user, @PathVariable UUID id) {
        permissions.exigir(user, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(reels.cancel(id, user.getWorkspace().getId())));
    }

    /** La aprobación no publica: solo deja constancia explícita de la revisión humana. */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<ReelRenderResponse>> approve(
            @AuthenticationPrincipal User user, @PathVariable UUID id) {
        permissions.exigir(user, Permission.POST_CREATE);
        return ResponseEntity.ok(ApiResponse.success(
                reels.approve(id, user.getWorkspace().getId(), user.getId())));
    }
}
