package com.metricol.api.service.social;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.service.publishing.PublishQueueService;

/**
 * Mete en la cola las programadas a las que ya les toca salir.
 *
 * <p>Ya NO publica: solo encola. Antes esta clase publicaba de una en una, en
 * su propio hilo, con un lote de veinte por minuto — así que veinte
 * publicaciones lentas ocupaban el minuto entero y las de la vuelta siguiente
 * salían tarde. Ahora el trabajo lo reparte {@code PublishJobDispatcher}
 * entre varios workers, y esta clase se limita a decir cuáles ya vencieron.
 *
 * <p>Es sobre todo una red de seguridad. Lo normal es que una programada entre
 * a la cola en el momento de guardarla, con su hora de salida; esto recoge las
 * que se guardaron antes de que la cola existiera, o aquellas cuyo encolado se
 * perdió.
 */
@Component
public class ScheduledPostWorker {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPostWorker.class);

    private final PostRepository postRepository;
    private final PublishQueueService cola;

    /**
     * Cuántas se recogen por vuelta. Ya no es el tope de concurrencia —eso lo
     * pone el pool de la cola— sino solo un tope de cuántas filas se tocan de
     * golpe tras un rezago largo.
     */
    @Value("${app.scheduling.batch-size:200}")
    private int tope;

    public ScheduledPostWorker(PostRepository postRepository, PublishQueueService cola) {
        this.postRepository = postRepository;
        this.cola = cola;
    }

    @Scheduled(fixedDelayString = "${app.scheduling.poll-delay-ms:60000}")
    public void encolarVencidas() {
        List<Object[]> vencidas;
        try {
            vencidas = postRepository.findProgramadasVencidas(LocalDateTime.now(), tope);
        } catch (Exception ex) {
            // Un fallo aquí no debe matar la tarea: fixedDelay deja de
            // reprogramarse si el metodo lanza, y el worker quedaria muerto
            // hasta el siguiente reinicio.
            log.error("No se pudo consultar las publicaciones programadas: {}", ex.getMessage());
            return;
        }

        if (vencidas.isEmpty()) {
            return;
        }

        for (Object[] fila : vencidas) {
            String postId = String.valueOf(fila[0]);
            Object tenant = fila[1];

            if (tenant == null || String.valueOf(tenant).isBlank()) {
                log.warn("La publicacion {} no tiene workspace; se omite.", postId);
                continue;
            }

            // Cada una en su propio intento: si una revienta, las demas de la
            // vuelta tienen que encolarse igual.
            try {
                UUID workspaceId = UUID.fromString(String.valueOf(tenant));
                UUID id = UUID.fromString(postId);
                // El tenant impuesto es lo que hace que el post se encuentre:
                // Hibernate filtra por TenantId y aqui no hay usuario del cual
                // deducirlo.
                TenantIdentifierResolver.comoTenant(
                        workspaceId.toString(),
                        () -> cola.encolarAhora(id, workspaceId));
            } catch (Exception ex) {
                log.error("No se pudo encolar la programada {}: {}", postId, ex.getMessage());
            }
        }

        log.info("Programadas vencidas revisadas: {}", vencidas.size());
    }
}
