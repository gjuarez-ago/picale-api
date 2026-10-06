package com.metricol.api.service.metricas;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import com.metricol.api.config.UploadPostProperties;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.service.social.UploadPostClient;

/**
 * Cada rato pregunta a las redes cómo les va a las publicaciones recientes y
 * lo guarda en cada destino ({@link PostTarget#getVistas()} y compañía). Es lo
 * que deja al agente aprender a qué hora y con qué hashtags le va mejor a cada
 * cuenta (ver {@link LoQueFunciona}).
 *
 * <p>upload-post limita la lectura en vivo a 100 consultas cada 5 minutos, y
 * ese tope es de toda la plataforma: por vuelta se leen como mucho
 * {@code app.metricas.por-vuelta} (60) con una pausa entre cada una, y si el
 * proveedor dice "demasiadas" la vuelta se corta ahí.
 *
 * <p>Su propio hilo, como el agente: son decenas de llamadas lentas y el
 * programador de Spring tiene uno solo para todo.
 */
@Component
public class MetricasWorker {

    private static final Logger log = LoggerFactory.getLogger(MetricasWorker.class);

    private final PostTargetRepository destinos;
    private final UploadPostClient client;
    private final UploadPostProperties props;
    private final LoQueFunciona loQueFunciona;

    @Value("${app.metricas.enabled:true}")
    private boolean habilitado;

    @Value("${app.metricas.por-vuelta:60}")
    private int porVuelta;

    @Value("${app.metricas.pausa-ms:2500}")
    private long pausaMs;

    private final java.util.concurrent.ExecutorService hilo = java.util.concurrent.Executors.newSingleThreadExecutor(t -> {
        Thread h = new Thread(t, "metricas");
        h.setDaemon(true);
        return h;
    });
    private final java.util.concurrent.atomic.AtomicBoolean enCurso = new java.util.concurrent.atomic.AtomicBoolean();

    public MetricasWorker(PostTargetRepository destinos, UploadPostClient client, UploadPostProperties props,
            LoQueFunciona loQueFunciona) {
        this.destinos = destinos;
        this.client = client;
        this.props = props;
        this.loQueFunciona = loQueFunciona;
    }

    @jakarta.annotation.PreDestroy
    void cerrar() {
        hilo.shutdownNow();
    }

    // Cada hora: lo del primer día se mide cada 3 h (la consulta decide a quién le toca).
    @Scheduled(fixedDelayString = "${app.metricas.delay-ms:3600000}",
            initialDelayString = "${app.metricas.initial-delay-ms:300000}")
    public void trabajar() {
        if (!habilitado || !props.isConfigured() || !enCurso.compareAndSet(false, true)) {
            return;
        }
        hilo.submit(() -> {
            try {
                vuelta();
            } catch (Exception ex) {
                log.error("La lectura de métricas falló: {}", ex.toString());
            } finally {
                enCurso.set(false);
            }
        });
    }

    /** @return cuántas publicaciones quedaron con números nuevos */
    int vuelta() {
        LocalDateTime ahora = LocalDateTime.now();
        List<Object[]> pendientes = destinos.porMedir(ahora.minusDays(14), ahora.minusHours(2), ahora.minusDays(2),
                ahora.minusHours(12), ahora.minusDays(2), ahora.minusDays(1), ahora.minusHours(3), porVuelta);
        int leidas = 0;
        for (Object[] fila : pendientes) {
            UUID id = UUID.fromString(String.valueOf(fila[0]));
            String red = String.valueOf(fila[1]).toLowerCase();
            String idEnLaRed = String.valueOf(fila[2]);
            String perfil = String.valueOf(fila[3]);
            LecturaDeMetricas.Metricas m;
            String aviso = null;
            try {
                java.util.Map<String, Object> respuesta = client.metricasDePublicacion(perfil, red, idEnLaRed);
                m = LecturaDeMetricas.leer(respuesta, red);
                aviso = LecturaDeMetricas.aviso(respuesta, red);
            } catch (HttpClientErrorException.TooManyRequests ex) {
                log.warn("upload-post pidió bajar el ritmo de métricas; sigue en la próxima vuelta");
                break;
            } catch (Exception ex) {
                // Una que falla (borrada en la red, una red sin métricas) no se
                // reintenta en cada vuelta: se apunta el intento y espera su turno.
                log.debug("Sin métricas para {} en {}: {}", idEnLaRed, red, ex.toString());
                m = null;
            }
            if (aviso != null && (m == null || m.vacias())) {
                destinos.marcarMedidoConAviso(id, LocalDateTime.now(), aviso);
            } else if (guardar(id, m)) {
                leidas++;
            }
            dormir();
        }
        if (leidas > 0) {
            loQueFunciona.olvidar();
            log.info("Métricas al día: {} de {} publicaciones", leidas, pendientes.size());
        }
        return leidas;
    }

    /**
     * Solo las columnas de métricas, con un UPDATE: no pisa el estado ni el
     * enlace que la publicación pudiera estar confirmando a la vez. Si la fila
     * ya no existe (se eliminó en medio) no pasa nada y sigue con la próxima.
     */
    private boolean guardar(UUID id, LecturaDeMetricas.Metricas m) {
        try {
            LocalDateTime ahora = LocalDateTime.now();
            if (m != null && !m.vacias()) {
                return destinos.guardarMetricas(id, m.vistas(), m.alcance(), m.meGusta(), m.comentarios(),
                        m.compartidos(), m.guardados(), ahora) == 1;
            }
            destinos.marcarMedido(id, ahora);
            return false;
        } catch (RuntimeException ex) {
            log.warn("No se pudieron guardar las métricas de {}: {}", id, ex.toString());
            return false;
        }
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
