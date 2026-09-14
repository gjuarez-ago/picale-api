package com.metricol.api.service.publishing;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.metricol.api.config.PublishingQueueProperties;
import com.metricol.api.service.publishing.PublishQueueService.Encargo;

/**
 * Reparte trabajos de la cola entre los workers libres.
 *
 * <p>Reclama exactamente lo que caben en el pool y nada más. La tentación es
 * vaciar la tabla de golpe y dejar que el pool haga fila, pero un trabajo
 * reclamado ya no lo puede tomar otra instancia: si este proceso se reinicia,
 * todo lo que tuviera esperando en memoria se queda marcado como reclamado y
 * no vuelve a salir hasta que venza el plazo de abandono. Reclamando solo lo
 * que se va a ejecutar ahora, lo demás sigue disponible para quien tenga
 * sitio.
 *
 * <p>Eso es también lo que hace que esto escale a lo ancho: dos, cinco o diez
 * instancias del API pueden correr este mismo despachador contra la misma
 * tabla sin coordinarse entre ellas ni pisarse un trabajo, porque quien
 * decide quién se lo queda es el update condicional de
 * {@code PublishJobRepository.reclamar}.
 */
@Component
public class PublishJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PublishJobDispatcher.class);

    private final PublishQueueService cola;
    private final PublishJobRunner runner;
    private final ThreadPoolTaskExecutor executor;
    private final PublishingQueueProperties props;
    private final ProviderCircuitBreaker breaker;

    /**
     * Quién reclamó cada trabajo. Sirve para diagnosticar —qué instancia se
     * quedó colgada—, no para coordinar: la exclusión la hace la base.
     */
    private final String identidad;

    public PublishJobDispatcher(
            PublishQueueService cola,
            PublishJobRunner runner,
            @Qualifier("publishExecutor") ThreadPoolTaskExecutor executor,
            PublishingQueueProperties props,
            ProviderCircuitBreaker breaker) {
        this.cola = cola;
        this.runner = runner;
        this.executor = executor;
        this.props = props;
        this.breaker = breaker;
        this.identidad = "api-" + ProcessHandle.current().pid();
    }

    @Scheduled(fixedDelayString = "${app.publishing.queue.poll-delay-ms:2000}")
    public void despachar() {
        try {
            repartir();
        } catch (Exception ex) {
            // Un fallo aquí no debe matar la tarea: fixedDelay deja de
            // reprogramarse si el metodo lanza, y el despachador quedaria
            // muerto hasta el siguiente reinicio. La cola seguiria llenandose
            // sin que nadie la vaciara, y sin un solo error nuevo en el log.
            log.error("El despachador de publicaciones falló esta vuelta: {}", ex.getMessage());
        }
    }

    private void repartir() {
        // El proveedor pidio bajar el ritmo: no se reparte nada hasta que
        // venza la pausa. Repartir igual seria mandar ocho peticiones mas
        // contra el mismo tope, que es justo lo que convierte un aviso en
        // un bloqueo de la llave.
        if (breaker.abierto()) {
            log.debug("Cola en pausa hasta {} por peticion del proveedor", breaker.hasta());
            return;
        }

        int libres = executor.getMaxPoolSize() - executor.getActiveCount();
        if (libres <= 0) {
            return;
        }

        int tope = Math.min(libres, props.getBatchSize());
        List<UUID> candidatos = cola.siguientes(tope);
        if (candidatos.isEmpty()) {
            return;
        }

        int lanzados = 0;
        for (UUID jobId : candidatos) {
            Encargo encargo = cola.reclamar(jobId, identidad);
            if (encargo == null) {
                // Otra instancia se lo llevó primero. Normal, no es un error.
                continue;
            }

            try {
                executor.execute(() -> runner.ejecutar(encargo));
                lanzados++;
            } catch (RejectedExecutionException ex) {
                // El pool se llenó entre el conteo y el envío. Se devuelve
                // para que lo tome quien tenga sitio, en vez de perderlo.
                cola.devolverALaCola(encargo.jobId());
                break;
            }
        }

        if (lanzados > 0) {
            log.debug("Publicaciones lanzadas: {} (workers ocupados: {}/{})",
                    lanzados, executor.getActiveCount(), executor.getMaxPoolSize());
        }
    }

    /**
     * Rescata los trabajos de un proceso que murió con ellos en la mano. Corre
     * espaciado porque no hay prisa —el plazo de abandono ya es de minutos— y
     * porque es la única consulta de la cola que recorre trabajos que no le
     * tocan a nadie.
     */
    @Scheduled(fixedDelayString = "${app.publishing.queue.rescue-delay-ms:120000}")
    public void rescatar() {
        try {
            int rescatados = cola.rescatarAbandonados();
            if (rescatados > 0) {
                log.warn("Publicaciones rescatadas de un worker interrumpido: {}", rescatados);
            }
        } catch (Exception ex) {
            log.error("No se pudo rescatar publicaciones abandonadas: {}", ex.getMessage());
        }
    }
}
