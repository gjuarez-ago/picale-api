package com.metricol.api.service.agente;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.metricol.api.config.TenantIdentifierResolver;

/**
 * Lo que la persona le pide al asistente y tarda (mejorar una foto, preparar
 * una pieza): se contesta de inmediato y se hace aquí, en otro hilo, dentro de
 * su espacio. Al terminar, el aviso al teléfono dice cómo salió.
 *
 * <p>Con {@code app.agente.pedidos-en-linea=true} corre en el mismo hilo: las
 * pruebas ven el resultado sin esperar.
 */
@Component
public class TrabajoDeFondo {

    private static final Logger log = LoggerFactory.getLogger(TrabajoDeFondo.class);

    private final ExecutorService hilos = Executors.newFixedThreadPool(2, t -> {
        Thread h = new Thread(t, "agente-pedidos");
        h.setDaemon(true);
        return h;
    });

    @Value("${app.agente.pedidos-en-linea:false}")
    private boolean enLinea;

    @jakarta.annotation.PreDestroy
    void cerrar() {
        hilos.shutdownNow();
    }

    public void enEspacio(UUID workspaceId, Runnable trabajo) {
        Runnable dentro = () -> {
            try {
                TenantIdentifierResolver.comoTenant(workspaceId.toString(), trabajo);
            } catch (RuntimeException ex) {
                log.error("Un pedido al asistente falló en {}: {}", workspaceId, ex.toString());
            }
        };
        if (enLinea) {
            dentro.run();
        } else {
            hilos.submit(dentro);
        }
    }
}
