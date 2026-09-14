package com.metricol.api.config;

import java.time.LocalDateTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Cómo se comporta la cola de publicación.
 *
 * <p>El número de workers es, a la vez, el tope de llamadas simultáneas a
 * upload-post. No hay un semáforo aparte para eso a propósito: un hilo
 * esperando un permiso mientras sostiene una transacción abierta agota el
 * pool de conexiones antes que el del proveedor, así que el límite se pone
 * donde no cuesta nada — en el tamaño del pool que reclama trabajos.
 */
@Configuration
@ConfigurationProperties(prefix = "app.publishing.queue")
@Getter
@Setter
public class PublishingQueueProperties {

    /**
     * Workers que publican en paralelo. También es el tope de llamadas
     * simultáneas al proveedor.
     */
    private int workers = 8;

    /** Cada cuánto el despachador busca trabajos pendientes. */
    private long pollDelayMs = 2000;

    /** Cuántos reclama como máximo en una vuelta, aunque haya sitio libre. */
    private int batchSize = 25;

    /** Intentos totales por publicación, incluido el primero. */
    private int maxAttempts = 4;

    /** Espera antes del primer reintento; se duplica en cada uno. */
    private long retryBaseSeconds = 30;

    /** Techo de la espera entre reintentos, para que no crezca sin fin. */
    private long retryMaxSeconds = 900;

    /**
     * Tras cuánto tiempo un trabajo {@code RUNNING} se da por abandonado y
     * vuelve a la cola. Cubre el caso de un proceso que se murió con el
     * trabajo en la mano: sin esto se quedaría RUNNING para siempre y nadie
     * volvería a tocarlo.
     */
    private long staleAfterSeconds = 600;

    /** Cuándo reintentar tras el intento número {@code intento}. */
    public LocalDateTime siguienteIntento(int intento) {
        long espera = retryBaseSeconds;
        for (int i = 1; i < Math.max(intento, 1); i++) {
            espera *= 2;
            if (espera >= retryMaxSeconds) {
                espera = retryMaxSeconds;
                break;
            }
        }
        // Un poco de dispersión: si cien publicaciones fallaron por la misma
        // caída del proveedor, reintentarlas todas en el mismo segundo lo
        // volvería a tumbar.
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Math.max(2, espera / 4));
        return LocalDateTime.now().plusSeconds(espera + jitter);
    }
}
