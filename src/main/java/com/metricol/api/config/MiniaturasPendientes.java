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
            com.metricol.api.repository.PostRepository posts,
            com.metricol.api.service.storage.R2StorageService storage) {
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
            if (!pendientes.isEmpty()) {
                log.info("Sacando miniatura a {} videos que no la tenian", pendientes.size());
                // Con su workspace: este hilo no tiene usuario del cual deducirlo,
                // y sin el la fila no se encuentra (ver MiniaturasEnSegundoPlano).
                pendientes.forEach(asset -> miniaturas.sacar(asset.getId(), asset.getTenantId()));
            }

            rehacerLasPerdidas(repository, miniaturas, storage);
        };
    }

    /**
     * Las miniaturas que la fila apunta pero que ya no están en R2.
     *
     * <p>Pasó en octubre de 2026: una API de desarrollo, con su base local
     * pero el bucket de producción, barrió {@code derivados/} contra SU base
     * —que no conocía los videos de producción— y borró todas las
     * miniaturas. Las filas siguieron apuntando a ellas y la galería se llenó
     * de imágenes rotas, sin que nada las fuera a rehacer: la regla de arriba
     * solo mira las que no tienen URL.
     *
     * <p>Una sola lista de R2 para todas, no una consulta por video.
     */
    static void rehacerLasPerdidas(MediaAssetRepository repository, MiniaturasEnSegundoPlano miniaturas,
            com.metricol.api.service.storage.R2StorageService storage) {
        try {
            java.util.Set<String> enR2 = new java.util.HashSet<>(
                    storage.listarClaves(com.metricol.api.service.media.MiniaturaDeVideo.prefijo()));
            List<MediaAsset> perdidas = repository.findVideosConMiniatura().stream()
                    .filter(a -> !enR2.contains(
                            com.metricol.api.service.media.MiniaturaDeVideo.claveDe(a.getStorageKey())))
                    // Solo las nuestras: una URL que no es de la carpeta de miniaturas no la hicimos
                    // aqui y no hay como rehacerla.
                    .filter(a -> a.getThumbnailUrl().contains(com.metricol.api.service.media.MiniaturaDeVideo.prefijo()))
                    .limit(TOPE)
                    .toList();
            if (perdidas.isEmpty()) {
                return;
            }
            log.warn("{} videos apuntan a una miniatura que ya no esta en R2; se vuelven a sacar", perdidas.size());
            perdidas.forEach(asset -> miniaturas.sacar(asset.getId(), asset.getTenantId(), true));
        } catch (Exception ex) {
            log.warn("No se pudo revisar si faltan miniaturas en R2: {}", ex.getMessage());
        }
    }

}
