package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.models.request.SocialAccountConnectRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.SocialAccountResponse;
import com.metricol.api.service.SocialAccountService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/social-accounts")
public class SocialAccountController {

    private final SocialAccountService service;

    public SocialAccountController(SocialAccountService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SocialAccountResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.list()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SocialAccountResponse>> connect(
            @Valid @RequestBody SocialAccountConnectRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.connect(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> disconnect(@PathVariable UUID id) {
        service.disconnect(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
