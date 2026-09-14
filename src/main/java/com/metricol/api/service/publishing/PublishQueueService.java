package com.metricol.api.service.publishing;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.config.PublishingQueueProperties;
import com.metricol.api.entity.PublishJob;
import com.metricol.api.enums.PublishJobStatus;
import com.metricol.api.repository.PublishJobRepository;

/**
 * La cola de publicación: encolar, reclamar y cerrar trabajos.
 *
 * <p>Todo lo que toca la tabla pasa por aquí, y cada método es su propia
 * transacción corta. Eso es deliberado: el trabajo largo —hablar con
 * upload-post, que puede tardar medio minuto— lo hace el runner FUERA de
 * estas transacciones. Si la publicación entera corriera dentro de una, ocho
 * workers publicando a la vez tendrían ocho conexiones de la base ocupadas
 * esperando a la red, y el pool se agotaría antes de que nadie notara nada.
 */
@Service
public class PublishQueueService {

    private static final Logger log = LoggerFactory.getLogger(PublishQueueService.class);

    /** Los que todavía pueden salir; el resto ya terminó de una forma u otra. */
    private static final List<PublishJobStatus> VIVOS = List.of(
            PublishJobStatus.QUEUED,
            PublishJobStatus.RUNNING,
            PublishJobStatus.RETRYING,
            PublishJobStatus.DEFERRED);

    /** Prioridad de lo que alguien está esperando en pantalla ahora mismo. */
    private static final int PRIORIDAD_INMEDIATA = 10;

    /** Prioridad de una programada: importa, pero nadie la está mirando. */
    private static final int PRIORIDAD_PROGRAMADA = 100;

    private final PublishJobRepository repository;
    private final PublishingQueueProperties props;

    public PublishQueueService(PublishJobRepository repository, PublishingQueueProperties props) {
        this.repository = repository;
        this.props = props;
    }

    /**
     * Mete una publicación en la cola para que salga cuanto antes.
     *
     * <p>Si ya tenía un trabajo vivo no crea otro: al guardar dos veces
     * seguidas —un doble toque, un reintento del cliente— la publicación
     * saldría dos veces en las redes, y eso no se puede deshacer.
     */
    @Transactional
    public PublishJob encolarAhora(UUID postId, UUID workspaceId) {
        return encolar(postId, workspaceId, LocalDateTime.now(), PRIORIDAD_INMEDIATA);
    }

    /** Mete una programada, para que se tome cuando llegue su hora. */
    @Transactional
    public PublishJob encolarPara(UUID postId, UUID workspaceId, LocalDateTime cuando) {
        return encolar(postId, workspaceId, cuando, PRIORIDAD_PROGRAMADA);
    }

    private PublishJob encolar(UUID postId, UUID workspaceId, LocalDateTime cuando, int prioridad) {
        if (repository.existsByPostIdAndStatusIn(postId, VIVOS)) {
            log.debug("La publicacion {} ya estaba en la cola; no se encola otra vez.", postId);
            return null;
        }

        PublishJob job = repository.save(PublishJob.builder()
                .workspaceId(workspaceId)
                .postId(postId)
                .status(PublishJobStatus.QUEUED)
                .runAt(cuando)
                .priority(prioridad)
                .attempts(0)
                .maxAttempts(props.getMaxAttempts())
                .build());

        log.debug("Encolada la publicacion {} del workspace {} para {}", postId, workspaceId, cuando);
        return job;
    }

    /** Los siguientes que tocan, hasta el tope. Solo ids: ver reclamar. */
    @Transactional(readOnly = true)
    public List<UUID> siguientes(int tope) {
        return repository.findTomables(PublishJobRepository.TOMABLES, LocalDateTime.now(), Limit.of(tope));
    }

    /**
     * Intenta quedarse con el trabajo. Devuelve el encargo si lo consiguió, o
     * {@code null} si otro worker llegó primero.
     */
    @Transactional
    public Encargo reclamar(UUID jobId, String worker) {
        int tomados = repository.reclamar(
                jobId, worker, LocalDateTime.now(), PublishJobRepository.TOMABLES);
        if (tomados == 0) {
            return null;
        }

        PublishJob job = repository.findById(jobId).orElse(null);
        if (job == null) {
            return null;
        }
        return new Encargo(
                job.getId(), job.getWorkspaceId(), job.getPostId(),
                job.getAttempts(), job.getMaxAttempts());
    }

    /**
     * Suelta un trabajo reclamado que no se llegó a ejecutar porque el pool
     * lo rechazó por estar lleno. Se descuenta el intento: no hubo ninguno, y
     * cobrárselo le comería sus reintentos de verdad.
     */
    @Transactional
    public void devolverALaCola(UUID jobId) {
        repository.findById(jobId).ifPresent(job -> {
            job.setStatus(PublishJobStatus.QUEUED);
            job.setAttempts(Math.max(0, job.getAttempts() - 1));
            job.setLockedBy(null);
            job.setLockedAt(null);
            job.setRunAt(LocalDateTime.now());
            repository.save(job);
        });
    }

    @Transactional
    public void marcarExito(UUID jobId, String detalle) {
        cerrar(jobId, PublishJobStatus.SUCCEEDED, detalle);
    }

    @Transactional
    public void marcarFallo(UUID jobId, String motivo) {
        cerrar(jobId, PublishJobStatus.FAILED, motivo);
    }

    private void cerrar(UUID jobId, PublishJobStatus estado, String detalle) {
        repository.findById(jobId).ifPresent(job -> {
            job.setStatus(estado);
            job.setFinishedAt(LocalDateTime.now());
            job.setLockedBy(null);
            job.setLockedAt(null);
            job.setLastError(recortar(detalle));
            repository.save(job);
        });
    }

    /**
     * Programa otro intento, o cierra como fallido si ya se gastaron todos.
     * Devuelve cuándo volverá a intentarse, o {@code null} si no habrá más.
     */
    @Transactional
    public LocalDateTime reintentar(UUID jobId, String motivo) {
        PublishJob job = repository.findById(jobId).orElse(null);
        if (job == null) {
            return null;
        }

        int tope = job.getMaxAttempts() > 0 ? job.getMaxAttempts() : props.getMaxAttempts();
        if (job.getAttempts() >= tope) {
            job.setStatus(PublishJobStatus.FAILED);
            job.setFinishedAt(LocalDateTime.now());
            job.setLastError(recortar(motivo));
            job.setLockedBy(null);
            job.setLockedAt(null);
            repository.save(job);
            log.warn("La publicacion {} agoto sus {} intentos: {}", job.getPostId(), tope, motivo);
            return null;
        }

        LocalDateTime cuando = props.siguienteIntento(job.getAttempts());
        job.setStatus(PublishJobStatus.RETRYING);
        job.setRunAt(cuando);
        job.setLastError(recortar(motivo));
        job.setLockedBy(null);
        job.setLockedAt(null);
        repository.save(job);
        log.info("La publicacion {} se reintenta el {} (intento {} de {})",
                job.getPostId(), cuando, job.getAttempts() + 1, tope);
        return cuando;
    }

    /**
     * Aplaza sin gastar un intento. Es para lo que no está roto: la cuota
     * diaria de la red se agotó y hay que esperar a que se reinicie. Cobrarle
     * un intento sería castigarla por algo que no falló.
     */
    @Transactional
    public void aplazar(UUID jobId, LocalDateTime cuando, String motivo) {
        repository.findById(jobId).ifPresent(job -> {
            job.setStatus(PublishJobStatus.DEFERRED);
            job.setRunAt(cuando);
            job.setAttempts(Math.max(0, job.getAttempts() - 1));
            job.setLastError(recortar(motivo));
            job.setLockedBy(null);
            job.setLockedAt(null);
            repository.save(job);
            log.info("La publicacion {} se aplaza hasta {}: {}", job.getPostId(), cuando, motivo);
        });
    }

    /** Cancela lo pendiente de un post que se borró o se está reescribiendo. */
    @Transactional
    public int cancelarDePost(UUID postId) {
        return repository.cancelarDePost(postId, VIVOS, LocalDateTime.now());
    }

    /**
     * Rescata los trabajos de un worker que se murió con ellos en la mano.
     * Sin esto se quedarían RUNNING para siempre: nadie los volvería a mirar
     * y esas publicaciones no saldrían nunca, sin un solo error en el log.
     */
    @Transactional
    public int rescatarAbandonados() {
        LocalDateTime ahora = LocalDateTime.now();
        return repository.liberarAbandonados(
                ahora.minusSeconds(props.getStaleAfterSeconds()),
                ahora,
                "El proceso que la tenia se interrumpio; vuelve a la cola.");
    }

    /** Cómo va la cola. Para la pantalla y para saber si se da abasto. */
    @Transactional(readOnly = true)
    public Resumen resumen(UUID workspaceId) {
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime masViejo = repository.masAntiguoPendiente(PublishJobRepository.TOMABLES, ahora);
        return new Resumen(
                repository.countByWorkspaceIdAndStatusIn(workspaceId, VIVOS),
                repository.countByStatus(PublishJobStatus.QUEUED)
                        + repository.countByStatus(PublishJobStatus.RETRYING),
                repository.countByStatus(PublishJobStatus.RUNNING),
                masViejo == null ? 0 : Math.max(0, Duration.between(masViejo, ahora).toSeconds()));
    }

    /** El texto de error cabe en 500; recortarlo es mejor que fallar al guardar. */
    private String recortar(String texto) {
        if (texto == null) {
            return null;
        }
        return texto.length() <= 500 ? texto : texto.substring(0, 497) + "...";
    }

    /**
     * Lo que el worker necesita saber, ya desligado de la base: el runner
     * corre en otro hilo y fuera de la transacción que lo reclamó, así que
     * pasarle la entidad daría una entidad separada de su sesión.
     */
    public record Encargo(UUID jobId, UUID workspaceId, UUID postId, int attempts, int maxAttempts) {
    }

    /**
     * El primero es lo que interesa a la persona; los demás son de la
     * instalación entera, y la espera es el que dice si la cola va al día:
     * los pendientes suben y bajan con el tráfico, pero una espera que crece
     * significa que faltan workers.
     */
    public record Resumen(long enColaDelWorkspace, long pendientes, long publicando, long esperaSegundos) {
    }
}
