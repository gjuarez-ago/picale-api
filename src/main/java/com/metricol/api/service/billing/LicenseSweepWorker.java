package com.metricol.api.service.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cada pocos minutos revisa las licencias: cubre a los workspaces sin licencia,
 * archiva los vencidos y restaura los que volvieron a pagar.
 *
 * <p>No necesita tenant: solo toca workspaces, licencias y créditos, que no
 * llevan {@code @TenantId}. Con los cobros apagados no hace nada.
 */
@Component
public class LicenseSweepWorker {

    private static final Logger log = LoggerFactory.getLogger(LicenseSweepWorker.class);

    private final LicenseService licencias;

    public LicenseSweepWorker(LicenseService licencias) {
        this.licencias = licencias;
    }

    @Scheduled(fixedDelayString = "${app.billing.sweep-delay-ms:600000}", initialDelayString = "${app.billing.sweep-initial-delay-ms:120000}")
    public void barrer() {
        try {
            licencias.procesarVencimientos();
        } catch (Exception ex) {
            // Un fallo aquí no debe tumbar nada: se reintenta en el siguiente ciclo.
            log.warn("Falló el barrido de licencias: {}", ex.toString());
        }
    }
}
