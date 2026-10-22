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

/**
 * Cierra las subidas que quedaron a medias.
 *
 * <p>Es la contrapartida de subir directo a R2. Al prefirmar se aparta el
 * espacio antes de que exista el archivo, y entre esos dos momentos puede
 * pasar cualquier cosa: el teléfono se queda sin batería, se cierra la app, se
 * va la red justo antes de confirmar. Sin esto, cada uno de esos casos dejaría
 * espacio apartado para siempre, y la barra de la app le diría a alguien que
 * tiene el disco lleno de archivos que nunca subió.
 *
 * <p>Qué hacer con cada uno lo decide {@link MediaAssetReconciler}; esta clase
 * solo busca cuáles y les impone su workspace.
 *
 * <p>El plazo es de una hora por defecto: una subida lenta en una red mala no
 * debe confundirse con una abandonada.
 */
@Component
public class MediaOrphanWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaOrphanWorker.class);

    private final MediaAssetRepository repository;
    private final MediaAssetReconciler reconciliador;

    /**
     * Para rescatar con la MISMA confirmacion que usa la app (arreglo de fotos, miniatura
     * de video): un archivo rescatado tiene que quedar igual que uno normal. Perezoso
     * para no forzar el orden de arranque del servicio de medios.
     */
    private com.metricol.api.service.MediaService media;

    @org.springframework.beans.factory.annotation.Autowired
    void setMedia(@org.springframework.context.annotation.Lazy com.metricol.api.service.MediaService media) {
        this.media = media;
    }

    /**
     * A los cuantos minutos se rescata una subida que YA esta completa en R2 pero que la app
     * no confirmo. El 22 oct 2026 la app se cayo a media tanda (5 videos y 7 fotos): los
     * archivos llegaron pero aparecieron hasta una hora despues, la persona reintento y el
     * mismo video quedo subido tres veces. Rescatar lo completo no puede esperar la hora que
     * si necesita descartar lo que nunca llego.
     */
    @Value("${app.media.rescate-after-minutes:3}")
    private long rescateMinutos;

    /**
     * Cuánto se espera antes de dar por abandonada una subida sin confirmar.
     * Tiene que ser holgadamente mayor que lo que vale una URL firmada
     * ({@code app.media.presign-ttl-seconds}): mientras la URL sigue siendo
     * válida, la subida todavía puede estar en curso.
     */
    @Value("${app.media.orphan-after-minutes:60}")
    private long plazoMinutos;

    @Value("${app.media.orphan-batch-size:100}")
    private int tope;

    public MediaOrphanWorker(MediaAssetRepository repository, MediaAssetReconciler reconciliador) {
        this.repository = repository;
        this.reconciliador = reconciliador;
    }

    /**
     * Confirma las subidas completas que la app dejo sin confirmar (se cerro, se quedo sin
     * bateria, Android la mato). Solo confirma: lo que todavia no esta completo en R2 se
     * queda pendiente -puede seguir subiendo- y lo resuelve revisarPendientes a la hora. Si
     * la app confirma al mismo tiempo no pasa nada: confirmar dos veces devuelve lo mismo.
     */
    @Scheduled(fixedDelayString = "${app.media.rescate-delay-ms:120000}")
    public void rescatarCompletas() {
        if (media == null) return;
        List<Object[]> pendientes;
        try {
            pendientes = repository.findPrefirmadosViejos(
                    LocalDateTime.now().minusMinutes(rescateMinutos), tope);
        } catch (Exception ex) {
            log.error("No se pudo consultar las subidas por rescatar: {}", ex.getMessage());
            return;
        }
        int rescatadas = 0;
        for (Object[] fila : pendientes) {
            Object tenant = fila[1];
            if (tenant == null || String.valueOf(tenant).isBlank()) continue;
            try {
                UUID id = UUID.fromString(String.valueOf(fila[0]));
                TenantIdentifierResolver.comoTenant(String.valueOf(tenant), () -> media.confirm(id));
                rescatadas++;
            } catch (Exception ex) {
                // Todavia subiendo o incompleta: se vuelve a mirar en la siguiente vuelta.
                log.debug("Subida {} aun no se puede confirmar: {}", fila[0], ex.getMessage());
            }
        }
        if (rescatadas > 0) {
            log.info("Subidas completas que la app no confirmo, rescatadas: {}", rescatadas);
        }
    }

    @Scheduled(fixedDelayString = "${app.media.orphan-delay-ms:900000}")
    public void revisarPendientes() {
        List<Object[]> pendientes;
        try {
            pendientes = repository.findPrefirmadosViejos(
                    LocalDateTime.now().minusMinutes(plazoMinutos), tope);
        } catch (Exception ex) {
            // Un fallo aquí no debe matar la tarea: fixedDelay deja de
            // reprogramarse si el metodo lanza, y esta limpieza quedaria
            // muerta hasta el siguiente reinicio sin un solo error nuevo.
            log.error("No se pudo consultar las subidas pendientes: {}", ex.getMessage());
            return;
        }

        if (pendientes.isEmpty()) {
            return;
        }

        int confirmados = 0;
        int descartados = 0;

        for (Object[] fila : pendientes) {
            String assetId = String.valueOf(fila[0]);
            Object tenant = fila[1];

            if (tenant == null || String.valueOf(tenant).isBlank()) {
                log.warn("La subida pendiente {} no tiene workspace; se omite.", assetId);
                continue;
            }

            // Cada una en su propio intento: si una revienta, las demas de la
            // vuelta tienen que resolverse igual.
            try {
                UUID id = UUID.fromString(assetId);
                boolean[] confirmado = { false };
                // El tenant se impone AQUI, antes de cruzar al metodo
                // transaccional: Hibernate resuelve el tenant al abrir la
                // sesion, asi que ponerlo dentro llegaria tarde y la fila no
                // se encontraria nunca.
                TenantIdentifierResolver.comoTenant(
                        String.valueOf(tenant),
                        () -> confirmado[0] = reconciliador.resolver(id));

                if (confirmado[0]) {
                    confirmados++;
                } else {
                    descartados++;
                }
            } catch (Exception ex) {
                log.error("No se pudo resolver la subida pendiente {}: {}", assetId, ex.getMessage());
            }
        }

        log.info("Subidas pendientes revisadas: {} confirmadas, {} descartadas",
                confirmados, descartados);
    }
}
