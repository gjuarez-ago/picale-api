package com.metricol.api.service.storage;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.repository.MediaAssetRepository;

/** Consulta global indexada; el tenant se aplica ANTES de abrir cada transacción. */
@Component
public class MultipartRecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(MultipartRecoveryWorker.class);
    private final MediaAssetRepository repository;
    private final CoordinadorDeSubidas coordinador;

    public MultipartRecoveryWorker(MediaAssetRepository repository, CoordinadorDeSubidas coordinador) {
        this.repository = repository;
        this.coordinador = coordinador;
    }

    @Scheduled(initialDelayString = "${app.media.multipart.recovery-initial-ms:120000}",
            fixedDelayString = "${app.media.multipart.recovery-delay-ms:120000}")
    public void recuperar() {
        for (Object[] fila : repository.multipartRecuperables(Instant.now(), 100)) {
            if (fila[1] == null || String.valueOf(fila[1]).isBlank()) continue;
            try {
                UUID id = UUID.fromString(String.valueOf(fila[0]));
                String tenant = String.valueOf(fila[1]);
                TenantIdentifierResolver.comoTenant(tenant, () -> coordinador.recuperar(id, UUID.fromString(tenant)));
            } catch (RuntimeException ex) {
                // Códigos persistidos por el coordinador; nunca URLs ni mensajes remotos.
                log.debug("Recuperación multipart {} pendiente ({})", fila[0], ex.getClass().getSimpleName());
            }
        }
    }
}
