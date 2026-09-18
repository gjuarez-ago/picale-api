package com.metricol.api.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.AiUsageReportResponse;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.service.ai.AiUsageReportService;
import com.metricol.api.service.publishing.PostPublishStore;
import com.metricol.api.service.social.ReconciliacionUploadPost;

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
    private final ReconciliacionUploadPost reconciliacion;
    private final String llaveConfigurada;

    public OpsController(
            AiUsageReportService reporte,
            ReconciliacionUploadPost reconciliacion,
            @Value("${app.ops.api-key:}") String llaveConfigurada) {
        this.reporte = reporte;
        this.reconciliacion = reconciliacion;
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
     * Vuelve a poner una publicación de acuerdo con lo que upload-post dice
     * hoy de ella, red por red.
     *
     * <p>Para publicaciones que quedaron guardadas al revés de la realidad: un
     * TikTok publicado que la app enseña como fallido, redes publicadas sin su
     * enlace. Pregunta con la misma cadena que usa el worker y sobrescribe lo
     * guardado con la respuesta. Lo que el proveedor no menciona no se toca.
     *
     * @param workspaceId el negocio dueño de la publicación; sin sesión no hay
     *                    otra forma de saber en qué tenant buscarla
     * @param requestId   el identificador del envío, solo si la publicación no
     *                    lo tiene guardado. Sale del registro del servidor:
     *                    "upload-post acepto la publicacion ... (envio X)".
     */
    @PostMapping("/publicaciones/{postId}/reconciliar")
    public ResponseEntity<ApiResponse<PostPublishStore.Reconciliacion>> reconciliar(
            @RequestHeader(value = "X-Ops-Key", required = false) String llave,
            @PathVariable UUID postId,
            @RequestParam UUID workspaceId,
            @RequestParam(required = false) String requestId) {

        exigirLlave(llave);
        return ResponseEntity.ok(ApiResponse.success(
                reconciliacion.reconciliar(workspaceId, postId, requestId)));
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
