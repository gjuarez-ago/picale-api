package com.metricol.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.DecisionRuteoResponse;
import com.metricol.api.service.agente.video.DecisionesDeRuteo;

@RestController
@RequestMapping("/api/v1/decisiones-ruteo")
public class DecisionRuteoController {

    private final DecisionesDeRuteo decisiones;

    public DecisionRuteoController(DecisionesDeRuteo decisiones) {
        this.decisiones = decisiones;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DecisionRuteoResponse>>> recientes(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "30") int dias,
            @RequestParam(defaultValue = "100") int limite) {
        return ResponseEntity.ok(ApiResponse.success(decisiones.recientes(user.getWorkspace().getId(), dias, limite)
                .stream().map(DecisionRuteoResponse::de).toList()));
    }

    @GetMapping("/publicacion/{postId}")
    public ResponseEntity<ApiResponse<List<DecisionRuteoResponse>>> dePublicacion(
            @AuthenticationPrincipal User user, @PathVariable UUID postId) {
        return ResponseEntity.ok(ApiResponse.success(decisiones.dePublicacion(user.getWorkspace().getId(), postId)
                .stream().map(DecisionRuteoResponse::de).toList()));
    }
}
