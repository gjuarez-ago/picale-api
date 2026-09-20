package com.metricol.api.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metricol.api.entity.CreditPack;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.response.ApiResponse;
import com.metricol.api.repository.CreditPackRepository;
import com.metricol.api.service.billing.BillingConfig;

/**
 * Los ajustes de cobros y los paquetes de créditos, para quien opera la
 * plataforma: encender los cobros, cambiar los días de prueba, ponerle su precio
 * de Stripe a un paquete. Sin SQL y sin desplegar.
 *
 * <p>Va con la llave de operación ({@code X-Ops-Key}), igual que
 * {@link OpsController}, y contesta 404 si falta o está mal: no confirma que
 * aquí haya algo que adivinar.
 */
@RestController
@RequestMapping("/api/v1/ops/billing")
public class OpsBillingController {

    private final BillingConfig config;
    private final CreditPackRepository paquetes;
    private final String llaveConfigurada;

    public OpsBillingController(BillingConfig config, CreditPackRepository paquetes,
            @Value("${app.ops.api-key:}") String llaveConfigurada) {
        this.config = config;
        this.paquetes = paquetes;
        this.llaveConfigurada = llaveConfigurada;
    }

    public record Valor(String valor) {
    }

    /** Lo que se puede cambiar de un paquete. Lo que no viene, no se toca. */
    public record CambioDePaquete(String name, Integer credits, Integer priceMinor, Boolean active,
            Integer sortOrder) {
    }

    public record PaqueteVista(String code, String name, int credits, Integer priceMinor, boolean active,
            boolean vendible, int sortOrder) {
        static PaqueteVista de(CreditPack p) {
            return new PaqueteVista(p.getCode(), p.getName(), p.getCredits(), p.getPriceMinor(), p.isActive(),
                    p.vendible(), p.getSortOrder());
        }
    }

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, String>>> ajustes(
            @RequestHeader(value = "X-Ops-Key", required = false) String llave) {
        exigirLlave(llave);
        return ResponseEntity.ok(ApiResponse.success(config.todos()));
    }

    @PutMapping("/settings/{clave}")
    public ResponseEntity<ApiResponse<Map<String, String>>> cambiarAjuste(
            @RequestHeader(value = "X-Ops-Key", required = false) String llave, @PathVariable String clave,
            @RequestBody Valor cuerpo) {
        exigirLlave(llave);
        config.guardar(clave, cuerpo.valor());
        return ResponseEntity.ok(ApiResponse.success(config.todos()));
    }

    @GetMapping("/packs")
    public ResponseEntity<ApiResponse<List<PaqueteVista>>> paquetes(
            @RequestHeader(value = "X-Ops-Key", required = false) String llave) {
        exigirLlave(llave);
        return ResponseEntity.ok(ApiResponse.success(
                paquetes.findAllByOrderBySortOrderAscCreditsAsc().stream().map(PaqueteVista::de).toList()));
    }

    @PutMapping("/packs/{codigo}")
    @Transactional
    public ResponseEntity<ApiResponse<PaqueteVista>> cambiarPaquete(
            @RequestHeader(value = "X-Ops-Key", required = false) String llave, @PathVariable String codigo,
            @RequestBody CambioDePaquete cambio) {
        exigirLlave(llave);
        CreditPack paquete = paquetes.findByCode(codigo)
                .orElseThrow(() -> new ResourceNotFoundException("Paquete no encontrado."));
        if (cambio.name() != null && !cambio.name().isBlank()) {
            paquete.setName(cambio.name().trim());
        }
        if (cambio.credits() != null) {
            if (cambio.credits() <= 0) {
                throw new IllegalArgumentException("Un paquete trae al menos un crédito.");
            }
            paquete.setCredits(cambio.credits());
        }
        if (cambio.priceMinor() != null) {
            // 0 lo quita; cualquier otro valor va en centavos y no baja del mínimo (349.00 se escribe 34900).
            if (cambio.priceMinor() < 0 || (cambio.priceMinor() > 0 && cambio.priceMinor() < BillingConfig.PRECIO_MINIMO)) {
                throw new IllegalArgumentException("El precio va en centavos y no baja de " + BillingConfig.PRECIO_MINIMO
                        + " ($10.00): $79.00 se escribe 7900. Usa 0 para quitarlo.");
            }
            paquete.setPriceMinor(cambio.priceMinor() == 0 ? null : cambio.priceMinor());
        }
        if (cambio.active() != null) {
            paquete.setActive(cambio.active());
        }
        if (cambio.sortOrder() != null) {
            paquete.setSortOrder(cambio.sortOrder());
        }
        paquete.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(ApiResponse.success(PaqueteVista.de(paquetes.save(paquete))));
    }

    private void exigirLlave(String llave) {
        boolean valida = llaveConfigurada != null && !llaveConfigurada.isBlank() && llave != null
                && MessageDigest.isEqual(llaveConfigurada.getBytes(StandardCharsets.UTF_8),
                        llave.getBytes(StandardCharsets.UTF_8));
        if (!valida) {
            throw new ResourceNotFoundException("Recurso no encontrado.");
        }
    }
}
