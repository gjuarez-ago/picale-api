package com.metricol.api.controller;

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
import com.metricol.api.models.request.WorkspaceCreateRequest;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.models.response.BillingSummaryResponse;
import com.metricol.api.models.response.BillingSummaryResponse.LicenseView;
import com.metricol.api.service.billing.BillingService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Pagos y licencias, siempre desde la web. La app móvil solo consulta el
 * estado ({@code GET /summary}); nunca vende nada.
 *
 * <p>Las compras devuelven una dirección de Stripe a la que hay que mandar a la
 * persona. Nada se activa aquí: eso ocurre cuando Stripe avisa que se pagó.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    /** Dirección de la página de pago (o del portal) de Stripe. */
    public record UrlResponse(String url) {
    }

    public record CreditCheckoutRequest(@NotBlank String packCode, UUID workspaceId) {
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<BillingSummaryResponse>> resumen(@AuthenticationPrincipal User usuario) {
        return ResponseEntity.ok(ApiResponse.success(billing.resumen(usuario)));
    }

    /** Un espacio nuevo: se manda a pagar y el espacio nace cuando se confirma el pago. */
    @PostMapping("/licenses/checkout")
    public ResponseEntity<ApiResponse<UrlResponse>> comprarEspacio(@AuthenticationPrincipal User usuario,
            @Valid @RequestBody WorkspaceCreateRequest datos) {
        return ResponseEntity.ok(ApiResponse.success(new UrlResponse(billing.comprarEspacioNuevo(usuario, datos))));
    }

    /** Ponerle licencia a un espacio que ya existe (en prueba, o archivado por falta de pago). */
    @PostMapping("/licenses/{workspaceId}/subscribe")
    public ResponseEntity<ApiResponse<UrlResponse>> contratar(@AuthenticationPrincipal User usuario,
            @PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(new UrlResponse(billing.contratarLicencia(usuario, workspaceId))));
    }

    /** Que no se renueve: se usa hasta el fin de lo pagado. */
    @PostMapping("/licenses/{workspaceId}/cancel")
    public ResponseEntity<ApiResponse<LicenseView>> cancelar(@AuthenticationPrincipal User usuario,
            @PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(billing.cancelarRenovacion(usuario, workspaceId, true)));
    }

    /** Arrepentirse de cancelar, mientras todavía no termina. */
    @PostMapping("/licenses/{workspaceId}/resume")
    public ResponseEntity<ApiResponse<LicenseView>> reanudar(@AuthenticationPrincipal User usuario,
            @PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(billing.cancelarRenovacion(usuario, workspaceId, false)));
    }

    @PostMapping("/credits/checkout")
    public ResponseEntity<ApiResponse<UrlResponse>> comprarCreditos(@AuthenticationPrincipal User usuario,
            @Valid @RequestBody CreditCheckoutRequest peticion) {
        UUID espacio = peticion.workspaceId() != null ? peticion.workspaceId() : usuario.getWorkspace().getId();
        return ResponseEntity.ok(ApiResponse.success(
                new UrlResponse(billing.comprarPaquete(usuario, peticion.packCode(), espacio))));
    }

    @PostMapping("/portal")
    public ResponseEntity<ApiResponse<UrlResponse>> portal(@AuthenticationPrincipal User usuario) {
        return ResponseEntity.ok(ApiResponse.success(new UrlResponse(billing.portal(usuario))));
    }
}
