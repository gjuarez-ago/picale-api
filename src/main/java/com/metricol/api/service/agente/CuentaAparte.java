package com.metricol.api.service.agente;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.metricol.api.config.TenantIdentifierResolver;

import jakarta.annotation.PreDestroy;

/**
 * Hace algo en OTRA cuenta desde una petición web: la bandeja de todas las
 * cuentas, el resumen, aprobar en otra cuenta sin cambiarse a ella.
 *
 * <p>No basta con {@link TenantIdentifierResolver#comoTenant} en el hilo de la
 * petición. Al entrar la petición, Spring ya abrió la sesión de Hibernate y la
 * ató al hilo (open-in-view) con la cuenta de la persona; el tenant que se
 * impone después llega tarde, y todas las consultas siguen leyendo la cuenta
 * actual. Así salían en la bandeja las propuestas de la cuenta actual con el
 * nombre de las otras. Es lo mismo que documenta {@code ReconciliacionUploadPost},
 * y la salida es la misma: un hilo limpio, sin sesión previa, donde el tenant
 * impuesto es el que se usa.
 */
@Component
public class CuentaAparte {

    /** Pocos hilos: es para leer una cuenta o aprobar una propuesta, no para trabajo largo. */
    private final ExecutorService hilos = Executors.newFixedThreadPool(2, tarea -> {
        Thread hilo = new Thread(tarea, "agente-otra-cuenta");
        hilo.setDaemon(true);
        return hilo;
    });

    @PreDestroy
    void cerrar() {
        hilos.shutdownNow();
    }

    /** El resultado de {@code trabajo} leído en la cuenta {@code workspaceId}. Los errores llegan tal cual. */
    public <T> T en(UUID workspaceId, Supplier<T> trabajo) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                AtomicReference<T> resultado = new AtomicReference<>();
                TenantIdentifierResolver.comoTenant(workspaceId.toString(), () -> resultado.set(trabajo.get()));
                return resultado.get();
            }, hilos).join();
        } catch (CompletionException ex) {
            // Que un 404 o un 400 lleguen al cliente como tales, no envueltos.
            if (ex.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw ex;
        }
    }

    /** Como {@link #en(UUID, Supplier)}, sin resultado. */
    public void en(UUID workspaceId, Runnable trabajo) {
        en(workspaceId, () -> {
            trabajo.run();
            return null;
        });
    }
}
