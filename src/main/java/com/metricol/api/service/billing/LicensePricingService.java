package com.metricol.api.service.billing;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.License;
import com.metricol.api.enums.LicenseStatus;
import com.metricol.api.repository.LicenseRepository;

/**
 * Que el precio completo lo lleve siempre alguien: cierra la grieta del precio
 * adicional.
 *
 * <p>El primer negocio de una organización paga el precio del plan y los demás
 * el adicional, que es más bajo. Sin esto bastaba con pagar el primero, agregar
 * otro y cancelar el primero para quedarse con un solo negocio a precio
 * adicional. Aquí, cuando una organización se queda con licencias pagadas y
 * vigentes pero NINGUNA al precio completo, la más antigua que siga
 * renovándose pasa al precio completo en su próxima renovación.
 *
 * <p>Corre en el barrido de licencias (cada pocos minutos), no en los avisos de
 * Stripe: así un fallo momentáneo de Stripe se reintenta solo. Y la promoción
 * ocurre cuando la licencia que llevaba el precio completo <b>ya terminó</b>, no
 * cuando se pidió cancelarla: mientras siga en uso ya está pagando el suyo.
 *
 * <p>Con un solo precio configurado (adicional igual al del plan) no hace nada.
 */
@Service
public class LicensePricingService {

    private static final Logger log = LoggerFactory.getLogger(LicensePricingService.class);

    private final BillingConfig config;
    private final StripeClient stripe;
    private final LicenseRepository licencias;
    private final StripeProductCatalog catalogo;

    public LicensePricingService(BillingConfig config, StripeClient stripe, LicenseRepository licencias,
            StripeProductCatalog catalogo) {
        this.config = config;
        this.stripe = stripe;
        this.licencias = licencias;
        this.catalogo = catalogo;
    }

    /** @return cuántas licencias pasaron al precio completo */
    @Transactional
    public int reconciliar() {
        if (!config.habilitado() || !stripe.disponible()) {
            return 0;
        }
        // Con un solo precio (o sin ninguno) no hay diferencia que corregir.
        if (config.listaDeLicencia() <= 0 || config.listaDeLicencia() == config.listaDeAdicional()) {
            return 0;
        }

        Map<UUID, List<License>> vigentesPorOrganizacion = licencias.findAll().stream()
                .filter(l -> l.getStripeSubscriptionId() != null
                        && (l.getStatus() == LicenseStatus.ACTIVE || l.getStatus() == LicenseStatus.PAST_DUE))
                .collect(Collectors.groupingBy(License::getOrganizationId));

        int promovidas = 0;
        for (List<License> vigentes : vigentesPorOrganizacion.values()) {
            if (vigentes.stream().anyMatch(l -> !l.isPricedAsExtra())) {
                continue; // alguien ya lleva el precio completo
            }
            // La que se va a quedar: la más antigua entre las que siguen renovándose. Si todas están
            // por terminar no hay a quién promover, y tampoco nada que cobrar de más.
            License candidata = vigentes.stream()
                    .filter(l -> !l.isCancelAtPeriodEnd())
                    .min(Comparator.comparing(License::getCreatedAt))
                    .orElse(null);
            if (candidata == null) {
                continue;
            }
            try {
                stripe.cambiarPrecio(candidata.getStripeSubscriptionId(), new StripeClient.Tarifa(
                        catalogo.productoDeLicencia(false), config.listaDeLicencia(), config.moneda(), true));
                candidata.setPricedAsExtra(false);
                candidata.setUpdatedAt(LocalDateTime.now());
                licencias.save(candidata);
                promovidas++;
                log.info("Cobros: la licencia del workspace {} pasa al precio completo en su próxima renovación",
                        candidata.getWorkspaceId());
            } catch (RuntimeException ex) {
                // Se reintenta en el próximo barrido.
                log.warn("No se pudo pasar la licencia del workspace {} al precio completo: {}",
                        candidata.getWorkspaceId(), ex.getMessage());
            }
        }
        return promovidas;
    }
}
