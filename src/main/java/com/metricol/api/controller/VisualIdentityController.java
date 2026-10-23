package com.metricol.api.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.metricol.api.entity.*;
import com.metricol.api.enums.Permission;
import com.metricol.api.models.request.VisualIdentityRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspace/brand/visual")
public class VisualIdentityController {
    private final VisualIdentityService service;
    private final PermissionService permissions;
    public VisualIdentityController(VisualIdentityService service, PermissionService permissions) { this.service = service; this.permissions = permissions; }
    @GetMapping
    public ApiResponse<VisualIdentity> get(@AuthenticationPrincipal User user) { return ApiResponse.success(service.get(user)); }
    @PutMapping
    public ApiResponse<VisualIdentity> update(@AuthenticationPrincipal User user, @Valid @RequestBody VisualIdentityRequest request) {
        permissions.exigir(user, Permission.WORKSPACE_EDIT);
        return ApiResponse.success(service.update(user, request));
    }
}
