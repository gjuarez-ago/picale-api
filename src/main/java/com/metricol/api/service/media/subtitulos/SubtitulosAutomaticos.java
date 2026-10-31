package com.metricol.api.service.media.subtitulos;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.metricol.api.config.TenantIdentifierResolver;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.Workspace;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.repository.WorkspaceRepository;

import jakarta.annotation.PreDestroy;

/**
 * Subtítulos automáticos: todo video que se sube a una cuenta que los tiene
 * encendidos sale con subtítulos, sin que nadie toque un botón.
 *
 * <p>El recorrido de un video original:
 * <ol>
 * <li>Al subirse queda {@link #ESPERANDO} (lo marca {@code MediaService}). El
 * agente no lo toma todavía: si lo tomara, propondría el original.</li>
 * <li>Este proceso saca el texto y encarga la copia con el estilo de la cuenta
 * (o uno al azar): queda {@link #HACIENDO}.</li>
 * <li>Cuando la cola de video termina la copia, queda {@link #LISTO} y el
 * agente lo toma; al preparar la publicación usa la copia con subtítulos.</li>
 * </ol>
 *
 * <p>Nunca se queda atorado: si nadie habla, si es muy largo, o si falla tres
 * veces, queda {@link #SIN_SUBTITULOS} y el agente sigue con el original.
 * Un video sin subtítulos es mejor que un video que nunca sale.
 */
@Service
public class SubtitulosAutomaticos {

    private static final Logger log = LoggerFactory.getLogger(SubtitulosAutomaticos.class);

    public static final String ESPERANDO = "ESPERANDO";
    public static final String HACIENDO = "HACIENDO";
    public static final String LISTO = "LISTO";
    public static final String SIN_SUBTITULOS = "SIN_SUBTITULOS";
    /** Ya traía subtítulos pegados: se suelta tal cual, sin ponerle otros encima. */
    public static final String YA_TENIA = "YA_TENIA";

    static final int MAX_INTENTOS = 3;

    private final MediaAssetRepository assets;
    private final WorkspaceRepository workspaces;
    private final SubtitulosService subtitulos;
    /** Uno a la vez: transcribir y encargar es corto, pero no debe correr dos veces el mismo video. */
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "subtitulos-auto");
        t.setDaemon(true);
        return t;
    });
    private final Set<UUID> enCurso = ConcurrentHashMap.newKeySet();

    public SubtitulosAutomaticos(MediaAssetRepository assets, WorkspaceRepository workspaces,
            SubtitulosService subtitulos) {
        this.assets = assets;
        this.workspaces = workspaces;
        this.subtitulos = subtitulos;
    }

    /**
     * Los ajustes de la cuenta, para el panel.
     *
     * @param estilo nulo = uno al azar en cada video
     * @param color  nulo = al azar
     */
    public record Ajustes(boolean activo, EstiloSubtitulos estilo, SubtitulosService.Color color) {
    }

    public Ajustes ajustes(UUID workspaceId) {
        Workspace espacio = workspaces.findById(workspaceId)
                .orElseThrow(() -> new com.metricol.api.exception.ResourceNotFoundException("Ese negocio no existe."));
        return new Ajustes(Boolean.TRUE.equals(espacio.getSubtitulosAuto()), estiloDe(espacio), colorDe(espacio));
    }

    public Ajustes guardar(UUID workspaceId, Ajustes nuevos) {
        Workspace espacio = workspaces.findById(workspaceId)
                .orElseThrow(() -> new com.metricol.api.exception.ResourceNotFoundException("Ese negocio no existe."));
        espacio.setSubtitulosAuto(nuevos.activo());
        espacio.setSubtitulosEstilo(nuevos.estilo() == null ? null : nuevos.estilo().name());
        espacio.setSubtitulosColor(nuevos.color() == null ? null : nuevos.color().name());
        workspaces.save(espacio);
        return ajustes(workspaceId);
    }

    /**
     * Cada 30 segundos, lo que espera. El trabajo va a un hilo propio: el
     * planificador de Spring tiene uno solo para todas las tareas, y
     * transcribir ahí frenaba la cola de publicaciones mientras tanto.
     */
    @Scheduled(initialDelay = 40000, fixedDelay = 30000)
    public void vuelta() {
        for (Object[] fila : assets.esperandoSubtitulos(10)) {
            encargar(UUID.fromString(String.valueOf(fila[0])), String.valueOf(fila[1]));
        }
        // Un video "haciendo" cuya copia ya no está en la cola (se borró, o
        // se cayó el servidor antes de crearla) se suelta: nunca se queda atorado.
        for (Object[] fila : assets.haciendoSinCopiaEnCurso(10)) {
            encargar(UUID.fromString(String.valueOf(fila[0])), String.valueOf(fila[1]));
        }
    }

    private void encargar(UUID id, String tenant) {
        if (!enCurso.add(id)) return;
        try {
            hilo.execute(() -> {
                try {
                    TenantIdentifierResolver.comoTenant(tenant, () -> procesar(id));
                } catch (Exception ex) {
                    log.warn("Subtítulos automáticos del video {}: {}", id, ex.toString());
                } finally {
                    enCurso.remove(id);
                }
            });
        } catch (RuntimeException ex) {
            enCurso.remove(id);
        }
    }

    @PreDestroy
    void apagar() {
        hilo.shutdownNow();
    }

    /** Un video que espera sus subtítulos: sacar el texto y encargar la copia. */
    void procesar(UUID id) {
        MediaAsset video = assets.findById(id).orElse(null);
        if (video == null) return;
        if (HACIENDO.equals(video.getSubtitulosEstado())) {
            soltarSiNoHayCopia(video);
            return;
        }
        if (!ESPERANDO.equals(video.getSubtitulosEstado())) return;
        UUID workspaceId = UUID.fromString(video.getTenantId());
        Workspace espacio = workspaces.findById(workspaceId).orElse(null);
        if (espacio == null || !Boolean.TRUE.equals(espacio.getSubtitulosAuto())) {
            // Los apagaron mientras esperaba: se suelta tal cual.
            guardar(video, null, video.getSubtitulosIntentos());
            return;
        }
        int intentos = (video.getSubtitulosIntentos() == null ? 0 : video.getSubtitulosIntentos()) + 1;
        try {
            subtitulos.texto(id, workspaceId);
            if (subtitulos.yaTraeSubtitulos(id, workspaceId)) {
                log.info("Video {} ya traía subtítulos: no se le ponen otros.", id);
                guardar(id, YA_TENIA, intentos);
                return;
            }
            // "Haciendo" ANTES de encargar la copia: si la cola la termina (o
            // la tira) en un instante, su aviso llega después de esta marca y
            // no al revés. Al revés, esta marca pisaba el "listo" y el agente
            // no tomaba nunca el video.
            guardar(id, HACIENDO, intentos);
            subtitulos.pedir(id, workspaceId, estiloDe(espacio), colorDe(espacio), null);
        } catch (IllegalArgumentException ex) {
            // No habla nadie, no tiene sonido, es muy largo: no es un fallo, no hay qué poner.
            log.info("Video {} sin subtítulos automáticos: {}", id, ex.getMessage());
            guardar(id, SIN_SUBTITULOS, intentos);
        } catch (SubtitulosService.ColaLlena ex) {
            // Muchos videos a la vez: espera su turno sin gastar un intento.
            guardar(id, ESPERANDO, intentos - 1);
        } catch (Exception ex) {
            log.warn("Subtítulos automáticos del video {} (intento {}): {}", id, intentos, ex.toString());
            guardar(id, intentos >= MAX_INTENTOS ? SIN_SUBTITULOS : ESPERANDO, intentos);
        }
    }

    private void soltarSiNoHayCopia(MediaAsset video) {
        if (!assets.copiasConSubtitulos(video.getId()).isEmpty()) {
            guardar(video, LISTO, video.getSubtitulosIntentos());
            return;
        }
        int intentos = video.getSubtitulosIntentos() == null ? 0 : video.getSubtitulosIntentos();
        guardar(video, intentos >= MAX_INTENTOS ? SIN_SUBTITULOS : ESPERANDO, intentos);
    }

    /**
     * La cola de video terminó una copia con subtítulos. Si el original la
     * estaba esperando, ya se suelta: con copia, o sin ella si falló tres
     * veces. Una copia pedida a mano no le cambia nada a un video que no la
     * esperaba (ni lo devuelve a la fila de los automáticos si falla).
     */
    public static void copiaTerminada(MediaAssetRepository assets, UUID originalId, boolean bien) {
        MediaAsset original = assets.findById(originalId).orElse(null);
        if (original == null || !HACIENDO.equals(original.getSubtitulosEstado())) return;
        int intentos = original.getSubtitulosIntentos() == null ? 0 : original.getSubtitulosIntentos();
        original.setSubtitulosEstado(bien ? LISTO : (intentos >= MAX_INTENTOS ? SIN_SUBTITULOS : ESPERANDO));
        assets.save(original);
    }

    /** Relee la fila antes de guardar: texto() guarda la transcripción en ella y no hay que pisarla. */
    private void guardar(UUID id, String estado, Integer intentos) {
        assets.findById(id).ifPresent(fresco -> guardar(fresco, estado, intentos));
    }

    private void guardar(MediaAsset video, String estado, Integer intentos) {
        video.setSubtitulosEstado(estado);
        video.setSubtitulosIntentos(intentos);
        assets.save(video);
    }

    static EstiloSubtitulos estiloDe(Workspace espacio) {
        try {
            return espacio.getSubtitulosEstilo() == null ? null : EstiloSubtitulos.valueOf(espacio.getSubtitulosEstilo());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static SubtitulosService.Color colorDe(Workspace espacio) {
        try {
            return espacio.getSubtitulosColor() == null ? null : SubtitulosService.Color.valueOf(espacio.getSubtitulosColor());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
