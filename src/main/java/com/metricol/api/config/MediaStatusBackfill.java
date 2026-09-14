package com.metricol.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.metricol.api.repository.MediaAssetRepository;

/**
 * Deja en READY los medios que ya estaban subidos antes de que existiera la
 * columna de estado.
 *
 * <p>Corre una vez al arrancar y no hace nada las siguientes veces. Existe
 * porque el esquema se actualiza con {@code ddl-auto=update} y no con
 * migraciones: la columna nueva se añade, pero queda en {@code null} en todo
 * lo que ya estaba. Y la galería pide {@code status = READY}, así que sin esto
 * el primer despliegue habría hecho desaparecer de la pantalla de Contenido
 * todos los archivos ya subidos —seguirían en R2, pagándose, sin que nadie
 * pudiera verlos ni borrarlos—.
 *
 * <p>Cuando este proyecto tenga migraciones de verdad, esto es lo primero que
 * debería mudarse a una: aquí está por la misma razón por la que el esquema se
 * genera solo, y las dos cosas se arreglan juntas.
 */
@Configuration
public class MediaStatusBackfill {

    private static final Logger log = LoggerFactory.getLogger(MediaStatusBackfill.class);

    @Bean
    public ApplicationRunner marcarMediosAntiguosComoListos(MediaAssetRepository repository) {
        return args -> {
            // Antes que nada: el CHECK que la base puso sobre los valores del
            // estado se quedó con los que había el día que se creó la tabla, y
            // `ddl-auto=update` no lo actualiza. Sin esto, escribir un estado
            // añadido después falla en producción y solo ahí — una base recién
            // creada nace con el check al día y no lo ve nadie.
            try {
                repository.quitarCheckDeEstado();
            } catch (Exception ex) {
                // H2 y Postgres lo nombran distinto y puede no existir. No es
                // condición para arrancar: si el check sigue puesto, lo que
                // falla es liberar videos, y eso se ve en su propio log.
                log.warn("No se pudo quitar el check de estado de media_assets: {}",
                        ex.getMessage());
            }

            try {
                int actualizados = repository.marcarAntiguosComoListos();
                if (actualizados > 0) {
                    log.info("Medios anteriores marcados como listos: {}", actualizados);
                }
            } catch (Exception ex) {
                // Que esto falle no puede impedir el arranque: es una
                // reparación de datos, no una condición para servir. El
                // síntoma sería una galería incompleta, no una app caída.
                log.error("No se pudo marcar los medios anteriores como listos: {}",
                        ex.getMessage());
            }
        };
    }
}
