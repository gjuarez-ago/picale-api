package com.metricol.api.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * El pool que publica.
 *
 * <p>Sin cola interna a propósito: {@code queueCapacity = 0}. La cola de
 * verdad es la tabla {@code publish_jobs}, y tener otra escondida en memoria
 * habría sido lo peor de las dos —un trabajo aceptado por el pool ya no está
 * disponible para otra instancia, y si el proceso muere se pierde sin que
 * nadie lo rescate, porque en la base sigue marcado como reclamado—. Con el
 * pool lleno, {@code execute} rechaza, el despachador devuelve el trabajo a
 * la cola y otro lo tomará; nada se queda esperando en un sitio del que no se
 * pueda recuperar.
 *
 * <p>El tamaño del pool es también el tope de llamadas simultáneas al
 * proveedor. Ahí es donde se limita el ritmo, y no con un semáforo dentro de
 * la publicación: un hilo esperando un permiso mientras sostiene una
 * transacción agota el pool de conexiones de la base mucho antes de que el
 * proveedor se queje.
 */
@Configuration
public class PublishingExecutorConfig {

    @Bean(name = "publishExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor publishExecutor(PublishingQueueProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int workers = Math.max(1, props.getWorkers());
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("publish-");
        // Que rechace en vez de ejecutar en el hilo del despachador: si
        // publicara ahí, el despachador se quedaría medio minuto sin buscar
        // trabajos y la cola parecería atascada.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // Al apagar, esperar a que las publicaciones en curso terminen. Cortar
        // a mitad dejaría un post que quizá ya salió en la red marcado como si
        // no, y el reintento lo publicaría dos veces.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Para el trabajo de ffmpeg que nadie espera: hoy, las miniaturas de los
     * videos.
     *
     * <p>Aparte del de publicar y no colgado de el, por dos motivos. El de
     * publicar tiene la cola en cero y rechaza lo que no cabe —esta pensado
     * para que el despachador no se quede ciego— asi que una miniatura o le
     * robaria un hueco a una publicacion o saldria rechazada. Y sacar un
     * fotograma no corre prisa: si tarda un minuto mas, lo unico que pasa es
     * que la galeria enseña el icono de siempre un rato mas.
     *
     * <p>Dos hilos y cola de cincuenta: ffmpeg gasta CPU, y mas hilos aqui
     * solo le quitarian aire a lo que si esta esperando alguien.
     */
    @Bean(name = "mediaExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor mediaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("media-");
        // Descarta la mas vieja en vez de reventar: con la cola llena, la
        // miniatura que lleva mas rato esperando es la menos util, y perder
        // una miniatura no rompe nada.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        // Sin esperar al apagar: es trabajo desechable, y retrasar el cierre
        // del servidor por un fotograma seria absurdo.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
