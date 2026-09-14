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
 * Borra los archivos que se subieron y no acabaron en ninguna publicación.
 *
 * <p>Sale de cómo sube la app: en cuanto se elige una foto empieza a subirla,
 * sin esperar a saber si esa publicación se va a guardar. Eso es lo que hace
 * que publicar sea instantáneo, y tiene un precio — cuando el
 * {@code POST /posts} falla, o cuando la persona se arrepiente y cierra la
 * pantalla, el archivo ya está arriba y confirmado. Nadie lo usa y nadie lo
 * mira: {@link MediaOrphanWorker} busca PENDING, y estos están READY.
 *
 * <p>Se notaba en la cuota, que es lo peor de todo: a alguien que no llegó a
 * publicar nada la barra le decía que tenía el espacio lleno, y lo que lo
 * llenaba no salía en la galería de ninguna publicación que pudiera borrar.
 *
 * <p><b>El plazo es largo a propósito.</b> Tres días, no una hora. La foto de
 * alguien que está componiendo sin prisa no se distingue de una abandonada más
 * que por el tiempo, y los dos errores no cuestan lo mismo: esperar de más
 * ocupa unos megas unos días, y borrar de más tira el trabajo de otro —o deja
 * una publicación apuntando a un archivo que ya no está, que falla al salir y
 * con un motivo que no ayuda—. La pantalla de captura sube en cuanto se elige
 * la foto, así que el reloj empieza a correr mientras la persona todavía
 * escribe, y una app que pasó la noche en segundo plano tiene que poder
 * publicar al día siguiente.
 *
 * <p>Los borradores no corren peligro en ningún caso: sus URLs están en
 * {@code post_media} desde que se guardan, así que cuentan como usadas.
 */
@Component
public class MediaSinUsarWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaSinUsarWorker.class);

    private final MediaAssetRepository repository;
    private final MediaService mediaService;

    @Value("${app.media.unused-after-hours:72}")
    private long plazoHoras;

    @Value("${app.media.unused-batch-size:100}")
    private int tope;

    public MediaSinUsarWorker(MediaAssetRepository repository, MediaService mediaService) {
        this.repository = repository;
        this.mediaService = mediaService;
    }

    @Scheduled(fixedDelayString = "${app.media.unused-delay-ms:3600000}")
    public void revisarSinUsar() {
        List<Object[]> candidatos;
        try {
            candidatos = repository.findListosSinUsar(
                    LocalDateTime.now().minusHours(plazoHoras), tope);
        } catch (Exception ex) {
            // Igual que en MediaOrphanWorker: con fixedDelay, un método que
            // lanza deja de reprogramarse, y la limpieza quedaría muerta hasta
            // el siguiente reinicio sin un solo error nuevo que lo delatara.
            log.error("No se pudo consultar los archivos sin usar: {}", ex.getMessage());
            return;
        }

        if (candidatos.isEmpty()) {
            return;
        }

        int borrados = 0;
        for (Object[] fila : candidatos) {
            String assetId = String.valueOf(fila[0]);
            Object tenant = fila[1];

            if (tenant == null || String.valueOf(tenant).isBlank()) {
                log.warn("El archivo sin usar {} no tiene workspace; se omite.", assetId);
                continue;
            }

            // Cada uno en su propio intento: si uno revienta, los demás de la
            // vuelta tienen que borrarse igual.
            try {
                UUID id = UUID.fromString(assetId);
                // El tenant se impone ANTES de cruzar al método transaccional
                // de MediaService: Hibernate lo resuelve al abrir la sesión, y
                // ponerlo dentro llegaría tarde —la sesión ya estaría abierta
                // como GLOBAL— y la fila no se encontraría nunca.
                TenantIdentifierResolver.comoTenant(
                        String.valueOf(tenant),
                        () -> mediaService.delete(id));
                borrados++;
            } catch (Exception ex) {
                log.error("No se pudo borrar el archivo sin usar {}: {}", assetId, ex.getMessage());
            }
        }

        if (borrados > 0) {
            log.info("Archivos sin usar borrados: {} de {} revisados", borrados, candidatos.size());
        }
    }
}
