package com.metricol.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.metricol.api.repository.WorkspaceRepository;

/**
 * Quita al arrancar el CHECK viejo de {@code workspaces.objetivo}, para que se
 * puedan guardar los objetivos que se agregaron después de los cinco primeros.
 * Ver {@link WorkspaceRepository#quitarCheckDeObjetivo()}.
 */
@Configuration
public class ObjetivoCheckBackfill {

    private static final Logger log = LoggerFactory.getLogger(ObjetivoCheckBackfill.class);

    @Bean
    public ApplicationRunner quitarCheckDeObjetivoDeWorkspaces(WorkspaceRepository repository) {
        return args -> {
            try {
                repository.quitarCheckDeObjetivo();
            } catch (Exception ex) {
                // H2 y Postgres lo nombran distinto y puede no existir. No es
                // condicion para arrancar.
                log.warn("No se pudo quitar el check de objetivo de workspaces: {}", ex.getMessage());
            }
        };
    }
}
