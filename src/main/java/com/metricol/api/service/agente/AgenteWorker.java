package com.metricol.api.service.agente;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.Workspace;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * Lo que hace que el agente trabaje solo: cada rato recorre las cuentas con el
 * agente encendido y le da una vuelta a cada una.
 *
 * <p>Nadie tiene que tocar un botón para que empiece ni para que siga. Una
 * subida de treinta fotos se procesa en varias vueltas, unas pocas por cuenta
 * cada vez, para que una cuenta grande no deje esperando a las demás.
 *
 * <p>Se puede apagar entero con {@code app.agente.enabled=false} sin tocar el
 * switch de nadie.
 */
@Component
public class AgenteWorker {

    private static final Logger log = LoggerFactory.getLogger(AgenteWorker.class);

    private final WorkspaceRepository workspaces;
    private final AgenteService agente;

    @Value("${app.agente.enabled:true}")
    private boolean habilitado;

    public AgenteWorker(WorkspaceRepository workspaces, AgenteService agente) {
        this.workspaces = workspaces;
        this.agente = agente;
    }

    @Scheduled(fixedDelayString = "${app.agente.delay-ms:120000}", initialDelayString = "${app.agente.initial-delay-ms:60000}")
    public void trabajar() {
        if (!habilitado) {
            return;
        }
        List<Workspace> encendidas;
        try {
            encendidas = workspaces.conAgenteEncendido();
        } catch (Exception ex) {
            // Con fixedDelay, un método que lanza deja de reprogramarse: el
            // agente quedaría muerto hasta reiniciar sin que nadie lo notara.
            log.error("El agente no pudo leer las cuentas encendidas: {}", ex.getMessage());
            return;
        }

        for (Workspace w : encendidas) {
            try {
                // El workspace se impone antes de entrar: Hibernate filtra por
                // él al abrir cada consulta, y sin usuario no hay de dónde sacarlo.
                TenantIdentifierResolver.comoTenant(w.getId().toString(), () -> {
                    int hechas = agente.vuelta(w.getId());
                    if (hechas > 0) {
                        log.info("Agente de {}: {} foto(s) revisadas", w.getId(), hechas);
                    }
                });
            } catch (Exception ex) {
                log.error("El agente falló en {}: {}", w.getId(), ex.toString());
            }
        }
    }
}
