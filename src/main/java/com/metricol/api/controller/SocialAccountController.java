package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.request.SocialAccountConnectRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.SocialAccountResponse;
import com.metricol.api.service.PermissionService;
import com.metricol.api.service.SocialAccountService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/social-accounts")
public class SocialAccountController {

    private final SocialAccountService service;
    private final PermissionService permisos;

    public SocialAccountController(SocialAccountService service, PermissionService permisos) {
        this.service = service;
        this.permisos = permisos;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SocialAccountResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.list()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SocialAccountResponse>> connect(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody SocialAccountConnectRequest request) {
        permisos.exigir(currentUser, Permission.NETWORK_MANAGE);
        return ResponseEntity.ok(ApiResponse.success(service.connect(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> disconnect(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        permisos.exigir(currentUser, Permission.NETWORK_MANAGE);
        service.disconnect(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
