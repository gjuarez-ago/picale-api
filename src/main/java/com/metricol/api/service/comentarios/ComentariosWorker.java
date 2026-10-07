package com.metricol.api.service.comentarios;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.config.UploadPostProperties;
import com.metricol.api.enums.Platform;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.service.social.UploadPostClient;

/**
 * Cada rato pregunta a las redes si alguien comentó algo nuevo.
 *
 * <p>upload-post no tiene webhook de comentarios —sus avisos son solo de
 * subidas y de conexiones—, así que no queda otra que preguntar. Y preguntar
 * cuesta: el tope de upload-post es por LLAVE, no por red, y lo comparte con
 * publicar y con leer métricas. Por eso esta vuelta es deliberadamente
 * tacaña:
 *
 * <ul>
 * <li>solo publicaciones de los últimos {@code app.comentarios.dias} días;
 * <li>solo si el contador de comentarios que ya trajeron las métricas SUBIÓ
 * desde la última revisión: si nadie comentó, no hay nada que pedir;
 * <li>tope de publicaciones por vuelta, con una pausa entre cada una;
 * <li>si el proveedor dice "demasiadas", la vuelta se corta ahí.
 * </ul>
 *
 * <p>Publicar siempre va antes que esto: si hay que elegir, lo que no puede
 * fallar es que salga la publicación.
 *
 * <p>Su propio hilo, como el agente y las métricas: son decenas de llamadas
 * lentas y el programador de Spring tiene uno solo para todo.
 */
@Component
public class ComentariosWorker {

    private static final Logger log = LoggerFactory.getLogger(ComentariosWorker.class);

    private final PostTargetRepository destinos;
    private final UploadPostClient client;
    private final ComentariosService comentarios;
    private final AvisoDeComentarios aviso;
    private final UploadPostProperties props;

    @Value("${app.comentarios.enabled:true}")
    private boolean habilitado;

    @Value("${app.comentarios.por-vuelta:40}")
    private int porVuelta;

    @Value("${app.comentarios.dias:30}")
    private int dias;

    @Value("${app.comentarios.pausa-ms:1500}")
    private long pausaMs;

    /**
     * Cupo que se le deja SIEMPRE a lo demás. El tope de upload-post es por
     * llave y lo comparten publicar y las métricas: leer comentarios nunca
     * puede ser el motivo por el que una publicación no sale.
     */
    @Value("${app.comentarios.reserva:15}")
    private int reserva;

    private final ExecutorService hilo = Executors.newSingleThreadExecutor(t -> {
        Thread h = new Thread(t, "comentarios");
        h.setDaemon(true);
        return h;
    });
    private final AtomicBoolean enCurso = new AtomicBoolean();

    public ComentariosWorker(PostTargetRepository destinos, UploadPostClient client, ComentariosService comentarios,
            AvisoDeComentarios aviso, UploadPostProperties props) {
        this.destinos = destinos;
        this.client = client;
        this.comentarios = comentarios;
        this.aviso = aviso;
        this.props = props;
    }

    @jakarta.annotation.PreDestroy
    void cerrar() {
        hilo.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${app.comentarios.delay-ms:600000}",
            initialDelayString = "${app.comentarios.initial-delay-ms:420000}")
    public void trabajar() {
        if (!habilitado || !props.isConfigured() || !enCurso.compareAndSet(false, true)) {
            return;
        }
        hilo.submit(() -> {
            try {
                vuelta();
            } catch (Exception ex) {
                log.error("La lectura de comentarios falló: {}", ex.toString());
            } finally {
                enCurso.set(false);
            }
        });
    }

    /** @return cuántos comentarios nuevos entraron */
    int vuelta() {
        LocalDateTime desde = LocalDateTime.now().minusDays(dias);
        List<Object[]> pendientes = destinos.conComentariosPorRevisar(desde, porVuelta);
        int nuevos = 0;
        for (Object[] fila : pendientes) {
            // Lo que dijo la última respuesta del proveedor. -1 = todavía no
            // lo sabemos, y entonces mandan la pausa y el tope por vuelta.
            int cupo = client.cupoRestante();
            if (cupo >= 0 && cupo <= reserva) {
                log.info("Queda poco cupo en upload-post ({}); los comentarios esperan a la próxima vuelta", cupo);
                break;
            }
            UUID destinoId = UUID.fromString(String.valueOf(fila[0]));
            Platform red;
            try {
                red = Platform.valueOf(String.valueOf(fila[1]).toUpperCase());
            } catch (IllegalArgumentException ex) {
                continue; // Una red que ya no manejamos.
            }
            if (!CapacidadesPorRed.seLeen(red)) {
                destinos.marcarComentariosRevisados(destinoId, LocalDateTime.now());
                continue;
            }
            String postIdEnLaRed = String.valueOf(fila[2]);
            String perfil = String.valueOf(fila[3]);
            UUID workspaceId = UUID.fromString(String.valueOf(fila[4]));
            UUID cuentaId = fila[5] == null ? null : UUID.fromString(String.valueOf(fila[5]));
            String cuentaPropia = fila[6] == null ? null : String.valueOf(fila[6]);

            ComentariosService.Destino destino = new ComentariosService.Destino(destinoId, workspaceId, cuentaId,
                    red, postIdEnLaRed, perfil, cuentaPropia);
            try {
                int[] cuenta = {0};
                TenantIdentifierResolver.comoTenant(workspaceId.toString(),
                        () -> cuenta[0] = comentarios.traer(destino));
                nuevos += cuenta[0];
                destinos.marcarComentariosRevisados(destinoId, LocalDateTime.now());
            } catch (HttpClientErrorException.TooManyRequests ex) {
                log.warn("upload-post pidió bajar el ritmo de comentarios; sigue en la próxima vuelta");
                break;
            } catch (Exception ex) {
                // Una publicación que falla (borrada en la red, cuenta sin
                // reconectar) no se reintenta en cada vuelta: se apunta el
                // intento y espera su turno como las demás.
                log.debug("Sin comentarios de {} en {}: {}", postIdEnLaRed, red, ex.toString());
                destinos.marcarComentariosRevisados(destinoId, LocalDateTime.now());
            }
            dormir();
        }
        if (nuevos > 0) {
            log.info("Comentarios nuevos: {} de {} publicaciones revisadas", nuevos, pendientes.size());
        }
        // Siempre, aunque esta vuelta no trajera nada: puede haber quedado algo
        // guardado de antes esperando a que se abra la ventana de avisos.
        aviso.avisarDeLoNuevo();
        return nuevos;
    }

    private void dormir() {
        if (pausaMs <= 0) {
            return;
        }
        try {
            Thread.sleep(pausaMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
