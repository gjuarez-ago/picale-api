package com.metricol.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.metricol.api.repository.AiUsageRepository;

/**
 * Quita al arrancar el CHECK viejo de `ai_usage.operacion`.
 *
 * <p>Sin esto, la primera imagen de campaña generada en producción fallaría al
 * anotar su gasto: la base creó la tabla con las operaciones que existían ese
 * día y `ddl-auto=update` no toca los checks. Ver
 * {@link AiUsageRepository#quitarCheckDeOperacion()}.
 */
@Configuration
public class AiUsageCheckBackfill {

    private static final Logger log = LoggerFactory.getLogger(AiUsageCheckBackfill.class);

    @Bean
    public ApplicationRunner quitarCheckDeOperacionDeAiUsage(AiUsageRepository repository) {
        return args -> {
            try {
                repository.quitarCheckDeOperacion();
            } catch (Exception ex) {
                // H2 y Postgres lo nombran distinto y puede no existir. No es
                // condicion para arrancar.
                log.warn("No se pudo quitar el check de operacion de ai_usage: {}", ex.getMessage());
            }
        };
    }
}
