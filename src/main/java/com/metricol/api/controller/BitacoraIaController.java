package com.metricol.api.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.models.response.AccionIaResponse;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.bitacora.BitacoraIa;

/**
 * La bitácora de lo que las IA conectadas hicieron en la cuenta actual.
 *
 * <p>Ver siempre se puede, como el resto de lo que hay en la cuenta: quien
 * está en el espacio tiene derecho a saber qué hizo la IA en él.
 */
@RestController
@RequestMapping("/api/v1/bitacora-ia")
public class BitacoraIaController {

    private final BitacoraIa bitacora;

    public BitacoraIaController(BitacoraIa bitacora) {
        this.bitacora = bitacora;
    }

    /** Lo más reciente primero. {@code dias} de 1 a 365; {@code limite} de 1 a 500. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<AccionIaResponse>>> listar(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "30") int dias,
            @RequestParam(defaultValue = "100") int limite) {
        return ResponseEntity.ok(ApiResponse.success(
                bitacora.listar(currentUser.getWorkspace().getId(), dias, limite).stream()
                        .map(AccionIaResponse::de).toList()));
    }

    /** El rastro de una publicación o un archivo: quién hizo qué con él. */
    @GetMapping("/{entidadId}")
    public ResponseEntity<ApiResponse<List<AccionIaResponse>>> de(
            @AuthenticationPrincipal User currentUser, @PathVariable String entidadId) {
        return ResponseEntity.ok(ApiResponse.success(
                bitacora.de(currentUser.getWorkspace().getId(), entidadId).stream()
                        .map(AccionIaResponse::de).toList()));
    }
}
