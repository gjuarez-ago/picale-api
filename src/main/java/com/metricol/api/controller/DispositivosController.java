package com.metricol.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.avisos.AvisosPush;

/** Los teléfonos de la persona, para mandarle avisos. Los registra la app al iniciar sesión. */
@RestController
@RequestMapping("/api/v1/dispositivos")
public class DispositivosController {

    public record Pedido(String token, String plataforma) {
    }

    private final AvisosPush avisos;

    public DispositivosController(AvisosPush avisos) {
        this.avisos = avisos;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> registrar(@AuthenticationPrincipal User currentUser,
            @RequestBody Pedido pedido) {
        avisos.registrar(currentUser, pedido == null ? null : pedido.token(),
                pedido == null ? null : pedido.plataforma());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /** Al cerrar sesión. POST y no DELETE con cuerpo: hay proxys que se lo quitan. */
    @PostMapping("/quitar")
    public ResponseEntity<ApiResponse<Void>> quitar(@AuthenticationPrincipal User currentUser,
            @RequestBody Pedido pedido) {
        avisos.quitar(currentUser, pedido == null ? null : pedido.token());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
