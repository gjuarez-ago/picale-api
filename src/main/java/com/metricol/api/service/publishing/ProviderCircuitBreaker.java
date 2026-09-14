package com.metricol.api.service.publishing;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * La pausa de toda la cola cuando el proveedor pide bajar el ritmo.
 *
 * <p>Un 429 de upload-post no es un problema de <i>esa</i> publicación: es de
 * la llave, que es una sola para todos los clientes. Antes cada trabajo lo
 * trataba como un fallo propio y lo reintentaba a los 30 segundos, y con ocho
 * workers eso convertía un «espera un poco» en ocho peticiones más contra el
 * mismo tope, hasta que los cuatro intentos se agotaban y las publicaciones
 * de <b>todos</b> los clientes acababan en FAILED en cascada.
 *
 * <p>Aquí el primer 429 cierra el paso: el despachador deja de repartir hasta
 * {@link #hasta()}, y el trabajo que lo recibió se aplaza —no se reintenta ni
 * gasta intento— para esa misma hora. Cuando vence, se vuelve a la normalidad
 * sin nada que rearmar.
 *
 * <p>Vive en memoria y por proceso. Con una sola instancia del API es exacto;
 * con varias, cada una se pausa cuando le toca su 429, que es suficiente: el
 * objetivo es no agravar, no coordinar al milisegundo.
 */
@Component
public class ProviderCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ProviderCircuitBreaker.class);

    private final AtomicReference<LocalDateTime> pausadoHasta = new AtomicReference<>();

    /** ¿Hay que esperar antes de hablar con el proveedor? */
    public boolean abierto() {
        LocalDateTime hasta = pausadoHasta.get();
        return hasta != null && LocalDateTime.now().isBefore(hasta);
    }

    /** Hasta cuándo, o {@code null} si no hay pausa (vigente o vencida). */
    public LocalDateTime hasta() {
        LocalDateTime hasta = pausadoHasta.get();
        return hasta != null && LocalDateTime.now().isBefore(hasta) ? hasta : null;
    }

    /**
     * Pausa la cola durante {@code pausa}. Si ya había una pausa más larga, se
     * respeta la más larga: dos 429 seguidos no acortan la espera.
     *
     * @return hasta cuándo queda pausada
     */
    public LocalDateTime abrir(Duration pausa, String motivo) {
        LocalDateTime propuesta = LocalDateTime.now().plus(pausa);
        LocalDateTime vigente = pausadoHasta.accumulateAndGet(propuesta,
                (actual, nueva) -> actual == null || nueva.isAfter(actual) ? nueva : actual);
        log.warn("Cola de publicacion en pausa hasta {} ({} s): {}", vigente, pausa.toSeconds(), motivo);
        return vigente;
    }

    /** Levanta la pausa a mano. Para pruebas y para operación. */
    public void cerrar() {
        pausadoHasta.set(null);
    }
}
