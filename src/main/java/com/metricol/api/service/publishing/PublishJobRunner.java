package com.metricol.api.service.publishing;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.service.publishing.PublishQueueService.Encargo;
import com.metricol.api.service.social.UploadPostPublisher;

/**
 * Lo que hace un worker con un trabajo ya reclamado: publicar y cerrar el
 * trabajo según cómo salió.
 *
 * <p>Separado del despachador porque son dos responsabilidades con ritmos
 * distintos —uno mira el reloj y reparte, el otro tarda lo que tarde la red—
 * y porque así el despachador se puede probar sin publicar nada.
 *
 * <p>Nada de aquí lanza hacia arriba: corre en un hilo del pool, donde una
 * excepción no la ve nadie y solo dejaría el trabajo marcado RUNNING para
 * siempre. Cualquier fallo se traduce en un reintento o en un fallo apuntado.
 */
@Service
public class PublishJobRunner {

    private static final Logger log = LoggerFactory.getLogger(PublishJobRunner.class);

    private final PublishQueueService cola;
    private final UploadPostPublisher publisher;
    private final PostPublishStore store;

    public PublishJobRunner(
            PublishQueueService cola,
            UploadPostPublisher publisher,
            PostPublishStore store) {
        this.cola = cola;
        this.publisher = publisher;
        this.store = store;
    }

    public void ejecutar(Encargo encargo) {
        boolean quedanIntentos = encargo.attempts() < encargo.maxAttempts();
        try {
            // El tenant impuesto es lo que hace que el post se encuentre:
            // Hibernate filtra por TenantId y aquí no hay usuario del cual
            // deducirlo. Sin esto el worker correría bien y no haría nada,
            // que es el sintoma que no apunta a ningun lado.
            TenantIdentifierResolver.comoTenant(
                    encargo.workspaceId().toString(),
                    () -> resolver(encargo, quedanIntentos));
        } catch (Exception ex) {
            // Aquí solo llega lo que ni el publisher pudo clasificar: un
            // fallo de la propia base, por ejemplo.
            log.error("Fallo inesperado publicando {}: {}", encargo.postId(), ex.getMessage(), ex);
            cerrarConFallo(encargo, "Error inesperado del servidor: " + ex.getMessage());
        }
    }

    private void resolver(Encargo encargo, boolean quedanIntentos) {
        PublishOutcome resultado = publisher.publish(
                encargo.postId(), encargo.workspaceId(), quedanIntentos);

        switch (resultado.result()) {
            case SUCCESS, PARTIAL -> cola.marcarExito(encargo.jobId(), resultado.detail());

            case DEFERRED -> cola.aplazar(
                    encargo.jobId(),
                    resultado.retryAt() != null ? resultado.retryAt() : LocalDateTime.now().plusHours(1),
                    resultado.detail());

            case FAILED_RETRYABLE -> {
                LocalDateTime proximo = cola.reintentar(encargo.jobId(), resultado.detail());
                if (proximo == null) {
                    // Se acabaron los intentos: hay que cerrar el post, o se
                    // quedaria "en cola" para siempre sin nadie detras.
                    store.marcarFallidaDefinitiva(encargo.postId(), resultado.detail());
                }
            }

            case FAILED_PERMANENT -> cola.marcarFallo(encargo.jobId(), resultado.detail());
        }
    }

    private void cerrarConFallo(Encargo encargo, String motivo) {
        try {
            cola.marcarFallo(encargo.jobId(), motivo);
            store.marcarFallidaDefinitiva(encargo.postId(), motivo);
        } catch (Exception ex) {
            // Si ni esto se puede escribir, el rescate de abandonados lo
            // recogera cuando venza su plazo. Solo queda dejar rastro.
            log.error("No se pudo cerrar el trabajo {}: {}", encargo.jobId(), ex.getMessage());
        }
    }
}
