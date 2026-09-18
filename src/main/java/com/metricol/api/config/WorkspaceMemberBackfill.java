package com.metricol.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import com.metricol.api.service.WorkspaceMembershipService;

/**
 * Da a cada usuario que ya existía la membresía de su workspace.
 *
 * <p>Corre al arrancar y no hace nada las siguientes veces. Existe por lo
 * mismo que {@link MediaStatusBackfill}: el esquema se actualiza con
 * {@code ddl-auto=update}, la tabla de membresías nace vacía, y sin esto la
 * lista de "mis workspaces" de toda la gente de antes saldría sin el suyo.
 *
 * <p>Cuando haya migraciones de verdad, esto se muda a una junto con aquel.
 */
@Configuration
public class WorkspaceMemberBackfill {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMemberBackfill.class);

    @Bean
    @Order(10)
    public ApplicationRunner completarMembresias(WorkspaceMembershipService membresias) {
        return args -> {
            try {
                int creadas = membresias.completarMembresiasFaltantes();
                if (creadas > 0) {
                    log.info("Membresias creadas para usuarios anteriores: {}", creadas);
                }
            } catch (Exception ex) {
                // No impide arrancar: sin la membresía, cada quien sigue en su
                // workspace de siempre; lo único que no podría es cambiar a otro.
                log.error("No se pudieron completar las membresias de workspace: {}", ex.getMessage());
            }
        };
    }
}
