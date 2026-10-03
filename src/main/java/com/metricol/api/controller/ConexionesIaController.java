package com.metricol.api.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.User;
import com.metricol.api.models.request.ConexionIaRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.ConexionIaResponse;
import com.metricol.api.service.bitacora.BitacoraIa;
import com.metricol.api.service.conexiones.ConexionesIa;

/**
 * Las IA conectadas a la cuenta de quien pregunta. El alta la hace el MCP al
 * terminar el login; la lista y la desconexión las usa el perfil en la web y
 * en la app. Todo es de la propia persona: nadie ve ni toca las de otro.
 */
@RestController
@RequestMapping("/api/v1/conexiones-ia")
public class ConexionesIaController {

    private final ConexionesIa conexiones;

    public ConexionesIaController(ConexionesIa conexiones) {
        this.conexiones = conexiones;
    }

    /** Solo el MCP da de alta conexiones: tiene que presentarse con X-Picale-Origen. */
    @PostMapping
    public ResponseEntity<ApiResponse<ConexionIaResponse>> crear(
            @AuthenticationPrincipal User currentUser,
            @RequestHeader(value = BitacoraIa.CABECERA_ORIGEN, required = false) String origen,
            @RequestBody ConexionIaRequest request) {
        if (origen == null || origen.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("SOLO_MCP",
                    "Las conexiones de IA las registra el servidor MCP, no la aplicación."));
        }
        if (request == null || request.cliente() == null || request.cliente().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", "Falta el nombre del cliente de IA."));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(ConexionIaResponse.de(
                conexiones.crear(currentUser, request.cliente(), request.clienteId(), request.expiraEpoch(), request.alcance()))));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ConexionIaResponse>>> listar(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                conexiones.listar(currentUser).stream().map(ConexionIaResponse::de).toList()));
    }

    /** Una conexión, con su bandera {@code activa}: el MCP la consulta al renovar tokens. */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ConexionIaResponse>> una(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ConexionIaResponse.de(conexiones.de(currentUser, id))));
    }

    /** Desconectar una IA. Desde ese instante sus llamadas reciben 401. */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<ConexionIaResponse>> revocar(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ConexionIaResponse.de(conexiones.revocar(currentUser, id))));
    }

    /** Desconectar todas las IA de la persona. */
    @DeleteMapping
    public ResponseEntity<ApiResponse<Map<String, Integer>>> revocarTodas(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(Map.of("revocadas", conexiones.revocarTodas(currentUser))));
    }
}
