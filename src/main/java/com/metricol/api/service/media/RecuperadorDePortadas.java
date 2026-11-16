package com.metricol.api.service.media;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.metricol.api.repository.MediaAssetRepository;

/** Evita que una portada perdida deje una subida fuera del agente indefinidamente. */
@Component
public class RecuperadorDePortadas {
    private static final Logger log = LoggerFactory.getLogger(RecuperadorDePortadas.class);
    private final MediaAssetRepository assets;
    private final MiniaturasEnSegundoPlano miniaturas;
    private final Clock reloj;
    private final Map<UUID, Instant> reintentarDesde = new HashMap<>();

    @Autowired
    public RecuperadorDePortadas(MediaAssetRepository assets, MiniaturasEnSegundoPlano miniaturas) {
        this(assets, miniaturas, Clock.systemDefaultZone());
    }

    RecuperadorDePortadas(MediaAssetRepository assets, MiniaturasEnSegundoPlano miniaturas, Clock reloj) {
        this.assets = assets;
        this.miniaturas = miniaturas;
        this.reloj = reloj;
    }

    @Scheduled(initialDelayString = "${app.media.portadas.initial-ms:120000}",
            fixedDelayString = "${app.media.portadas.delay-ms:120000}")
    public synchronized void revisar() {
        Instant ahora = reloj.instant();
        // Límite temporal de memoria; cada archivo fallido espera diez minutos.
        reintentarDesde.values().removeIf(t -> t.isBefore(ahora.minusSeconds(86400)));
        LocalDateTime local = LocalDateTime.now(reloj);
        try {
            int encargadas = 0;
            for (Object[] fila : assets.videosRecientesSinPortada(local.minusDays(7), local.minusMinutes(2))) {
                UUID id = UUID.fromString(String.valueOf(fila[0]));
                if (fila[1] == null || String.valueOf(fila[1]).isBlank()) continue;
                if (reintentarDesde.getOrDefault(id, Instant.MIN).isAfter(ahora)) continue;
                reintentarDesde.put(id, ahora.plusSeconds(600));
                try {
                    miniaturas.sacar(id, String.valueOf(fila[1]));
                    log.warn("Recuperando portada pendiente del video {}", id);
                } catch (RuntimeException ex) {
                    log.warn("No se pudo encargar la portada {}: {}", id, ex.toString());
                }
                if (++encargadas >= 5) break;
            }
        } catch (RuntimeException ex) {
            log.warn("No se pudieron revisar las portadas pendientes: {}", ex.toString());
        }
    }
}
