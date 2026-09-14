package com.metricol.api.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.AiUsageReportResponse;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.ai.AiUsageReportService;

/**
 * Endpoints de operación: lo que mira quien opera la plataforma, no un cliente.
 *
 * <p>Van con una llave propia ({@code X-Ops-Key}) y no con la sesión porque
 * aquí no hay un rol por encima de los workspaces: cada usuario es ADMIN del
 * suyo, y con su token vería los datos de todos los demás.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    private final AiUsageReportService reporte;
    private final String llaveConfigurada;

    public OpsController(AiUsageReportService reporte, @Value("${app.ops.api-key:}") String llaveConfigurada) {
        this.reporte = reporte;
        this.llaveConfigurada = llaveConfigurada;
    }

    /**
     * Cuánto gastó en IA cada workspace, del que más al que menos.
     *
     * <p>{@code desde} y {@code hasta} son días (2026-09-01), los dos
     * incluidos. Sin ellos, del día 1 del mes en curso a hoy.
     */
    @GetMapping("/ai-usage")
    public ResponseEntity<ApiResponse<AiUsageReportResponse>> aiUsage(
            @RequestHeader(value = "X-Ops-Key", required = false) String llave,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {

        exigirLlave(llave);

        LocalDate hoy = LocalDate.now();
        LocalDate inicio = desde != null ? desde : hoy.withDayOfMonth(1);
        LocalDate fin = hasta != null ? hasta : hoy;
        if (fin.isBefore(inicio)) {
            throw new IllegalArgumentException("'hasta' no puede ser anterior a 'desde'.");
        }

        return ResponseEntity.ok(ApiResponse.success(reporte.generar(inicio, fin)));
    }

    /**
     * Sin llave configurada, o con una equivocada: 404, y no 401 ni 403.
     *
     * <p>Un 401 confirmaría que aquí hay algo que vale la pena adivinar. Y la
     * comparación es de tiempo constante, para que tampoco se pueda adivinar
     * letra a letra midiendo cuánto tarda en contestar.
     */
    private void exigirLlave(String llave) {
        boolean valida = llaveConfigurada != null && !llaveConfigurada.isBlank() && llave != null
                && MessageDigest.isEqual(
                        llaveConfigurada.getBytes(StandardCharsets.UTF_8),
                        llave.getBytes(StandardCharsets.UTF_8));
        if (!valida) {
            throw new ResourceNotFoundException("Recurso no encontrado.");
        }
    }
}
