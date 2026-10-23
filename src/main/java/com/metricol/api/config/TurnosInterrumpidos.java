package com.metricol.api.config;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.metricol.api.repository.AssistantRunRepository;
import com.metricol.api.service.assistant.AssistantOrchestrator;

/**
 * Al arrancar, cierra los turnos del asistente que el reinicio dejó a medias.
 *
 * <p>Un turno corre en un hilo del servidor. Si el servidor se reinicia —un despliegue,
 * un corte— ese hilo muere y el turno se queda en {@code QUEUED} o {@code RUNNING} para
 * siempre: la web lo ve vivo, se queda en "Preparando el turno…" y no deja mandar otro
 * mensaje. Pasó en local el 20 oct 2026 al reiniciar la API con un mensaje recién mandado.
 *
 * <p>Solo los creados antes de este arranque: los de después tienen su hilo vivo.
 */
@Component
public class TurnosInterrumpidos {

    private static final Logger log = LoggerFactory.getLogger(TurnosInterrumpidos.class);

    private final AssistantRunRepository runs;
    private final AssistantOrchestrator orchestrator;
    private final LocalDateTime arranque = LocalDateTime.now();

    public TurnosInterrumpidos(AssistantRunRepository runs, AssistantOrchestrator orchestrator) {
        this.runs = runs;
        this.orchestrator = orchestrator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cerrar() {
        List<Object[]> pendientes;
        try {
            pendientes = runs.findInterrumpidos(arranque);
        } catch (Exception ex) {
            log.warn("No se pudieron revisar los turnos interrumpidos: {}", ex.getMessage());
            return;
        }
        int cerrados = 0;
        for (Object[] fila : pendientes) {
            String tenant = fila[1] == null ? null : String.valueOf(fila[1]);
            UUID id = UUID.fromString(String.valueOf(fila[0]));
            if (tenant == null || tenant.isBlank()) {
                continue;
            }
            try {
                boolean[] cerrado = {false};
                // Con su workspace: la fila lleva @TenantId y sin él no se encuentra.
                TenantIdentifierResolver.comoTenant(tenant, () -> cerrado[0] = orchestrator.cerrarInterrumpido(id));
                if (cerrado[0]) {
                    cerrados++;
                }
            } catch (Exception ex) {
                log.warn("No se pudo cerrar el turno interrumpido {}: {}", id, ex.getMessage());
            }
        }
        if (cerrados > 0) {
            log.info("Turnos del asistente que el reinicio dejó a medias, cerrados con aviso: {}", cerrados);
        }
    }
}
