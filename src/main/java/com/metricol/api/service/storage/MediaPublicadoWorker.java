package com.metricol.api.service.storage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.MediaService;

/**
 * Devuelve el espacio de los videos que ya se publicaron hace tiempo.
 *
 * <p>Era el agujero grande del almacenamiento: lo que se subía no salía nunca.
 * Los otros dos limpiadores cubren lo que no llegó a usarse
 * —{@link MediaOrphanWorker} las subidas a medias, {@link MediaSinUsarWorker}
 * las que ninguna publicación reclamó— pero lo que SÍ se publicó se quedaba
 * para siempre. Con videos de hasta cien megas, una cuenta se llenaba en dos
 * publicaciones y lo único que la vaciaba era borrar el historial a mano.
 *
 * <p><b>Solo videos.</b> Una foto pesa mil veces menos y es material que se
 * reutiliza: la pantalla de Contenido existe para eso. Un video se graba para
 * una publicación concreta, y una vez que salió cada red guarda su copia.
 *
 * <p><b>Y no se borra la fila ni la miniatura.</b> La publicación se sigue
 * viendo con su portada y el botón de verla lleva a la red, que es donde el
 * video está entero. Lo único que desaparece es el archivo que ya no servía
 * para nada salvo pagarse.
 */
@Component
public class MediaPublicadoWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaPublicadoWorker.class);

    private final MediaAssetRepository repository;
    private final MediaService mediaService;

    /**
     * Cuánto se espera desde que salió.
     *
     * <p>Treinta días: margen de sobra para volver a publicarlo en otra red o
     * descargarlo, y pasado un mes casi nadie vuelve al archivo original. Un
     * 0 apaga la liberación por completo.
     */
    @Value("${app.media.release-after-days:30}")
    private long plazoDias;

    @Value("${app.media.release-batch-size:50}")
    private int tope;

    public MediaPublicadoWorker(MediaAssetRepository repository, MediaService mediaService) {
        this.repository = repository;
        this.mediaService = mediaService;
    }

    /**
     * De madrugada, como las demás limpiezas: es una consulta con dos
     * subconsultas y una llamada a R2 por video, y nada de eso tiene por qué
     * competir con quien está publicando.
     */
    @Scheduled(cron = "${app.media.release-cron:0 10 4 * * *}")
    public void liberarPublicados() {
        if (plazoDias <= 0) {
            return;
        }

        List<Object[]> liberables;
        try {
            liberables = repository.findVideosLiberables(
                    LocalDateTime.now().minusDays(plazoDias), tope);
        } catch (Exception ex) {
            log.error("No se pudo consultar los videos liberables: {}", ex.getMessage());
            return;
        }

        if (liberables.isEmpty()) {
            return;
        }

        int liberados = 0;
        for (Object[] fila : liberables) {
            String assetId = String.valueOf(fila[0]);
            Object tenant = fila[1];

            if (tenant == null || String.valueOf(tenant).isBlank()) {
                log.warn("El video liberable {} no tiene workspace; se omite.", assetId);
                continue;
            }

            // Cada uno en su propio intento: si uno revienta, los demás de la
            // vuelta tienen que liberarse igual.
            try {
                UUID id = UUID.fromString(assetId);
                // El tenant se impone ANTES de cruzar al método transaccional
                // de MediaService: Hibernate lo resuelve al abrir la sesión, y
                // ponerlo dentro llegaría tarde —la sesión ya estaría abierta
                // como GLOBAL— y la fila no se encontraría nunca.
                TenantIdentifierResolver.comoTenant(
                        String.valueOf(tenant),
                        () -> mediaService.liberar(id));
                liberados++;
            } catch (Exception ex) {
                log.error("No se pudo liberar el video {}: {}", assetId, ex.getMessage());
            }
        }

        if (liberados > 0) {
            log.info("Videos publicados liberados: {} de {} revisados",
                    liberados, liberables.size());
        }
    }
}
