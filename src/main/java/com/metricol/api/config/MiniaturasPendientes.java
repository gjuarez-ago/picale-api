package com.metricol.api.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.media.MiniaturasEnSegundoPlano;

/**
 * Les saca miniatura a los videos que se subieron antes de que existiera esta
 * función.
 *
 * <p>Hace falta porque el esquema se actualiza con {@code ddl-auto=update} y
 * no con migraciones: la columna nueva aparece, pero queda en {@code null} en
 * todo lo que ya estaba. Sin esto, los videos viejos se quedarían con el icono
 * gris para siempre y solo los nuevos tendrían miniatura — una galería a dos
 * velocidades que nadie sabría explicar.
 *
 * <p>Con tope y sin bloquear el arranque: las genera el pool de medios, de dos
 * en dos, y si quedan videos para otra vez se hacen en el siguiente arranque.
 * Un servidor que tarda cinco minutos en levantar porque está sacando
 * fotogramas es peor problema que el que resuelve.
 */
@Configuration
public class MiniaturasPendientes {

    private static final Logger log = LoggerFactory.getLogger(MiniaturasPendientes.class);

    /** Cuántos se atienden por arranque. */
    private static final int TOPE = 50;

    @Bean
    public ApplicationRunner generarMiniaturasQueFaltan(
            MediaAssetRepository repository,
            MiniaturasEnSegundoPlano miniaturas,
            com.metricol.api.repository.PostRepository posts) {
        return args -> {
            // Primero se pegan a su publicacion las portadas que ya existen:
            // mientras vivan solo en la fila del archivo, borrar ese archivo
            // para hacer sitio se lleva la portada por delante. Ya paso con
            // una publicacion, y no se pudo recuperar.
            try {
                int fijadas = posts.fijarPortadasQueFaltan();
                if (fijadas > 0) {
                    log.info("Portadas pegadas a su publicacion: {}", fijadas);
                }
            } catch (Exception ex) {
                log.warn("No se pudieron fijar las portadas: {}", ex.getMessage());
            }

            List<MediaAsset> pendientes = repository.findVideosSinMiniatura(TOPE);
            if (pendientes.isEmpty()) {
                return;
            }
            log.info("Sacando miniatura a {} videos que no la tenian", pendientes.size());
            // Con su workspace: este hilo no tiene usuario del cual deducirlo,
            // y sin el la fila no se encuentra (ver MiniaturasEnSegundoPlano).
            pendientes.forEach(asset -> miniaturas.sacar(asset.getId(), asset.getTenantId()));
        };
    }
}
