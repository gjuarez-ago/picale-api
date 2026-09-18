package com.metricol.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import com.metricol.api.service.OrganizationService;

/**
 * Le da organización a los espacios de trabajo que ya existían.
 *
 * <p>Por lo mismo que {@link WorkspaceMemberBackfill}: el esquema se actualiza
 * con {@code ddl-auto=update}, la columna nace vacía, y un espacio sin
 * organización no puede administrar su equipo —ni saber quién manda en él—.
 *
 * <p>Corre DESPUÉS del de membresías ({@code @Order}), y no por casualidad:
 * para saber de quién es cada espacio se mira quién es su miembro más antiguo,
 * así que las membresías tienen que estar completas antes.
 */
@Configuration
public class OrganizationBackfill {

    private static final Logger log = LoggerFactory.getLogger(OrganizationBackfill.class);

    @Bean
    @Order(20)
    public ApplicationRunner completarOrganizaciones(OrganizationService organizaciones) {
        return args -> {
            try {
                int creadas = organizaciones.completarOrganizacionesFaltantes();
                if (creadas > 0) {
                    log.info("Organizaciones creadas para espacios anteriores: {}", creadas);
                }
            } catch (Exception ex) {
                // No impide arrancar: sin organización, cada quien sigue
                // trabajando en su espacio como hasta ahora; lo único que no
                // podría es administrar el equipo.
                log.error("No se pudieron completar las organizaciones: {}", ex.getMessage());
            }
        };
    }
}
