package com.metricol.api.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.PublishJob;
import com.metricol.api.enums.PublishJobStatus;

public interface PublishJobRepository extends JpaRepository<PublishJob, UUID> {

    /**
     * Los estados desde los que un trabajo puede tomarse. Se define aquí para
     * que la consulta que busca y la que reclama no se puedan desincronizar:
     * si difirieran, el despachador elegiría trabajos que el reclamo rechaza
     * y la cola parecería atascada sin motivo.
     */
    List<PublishJobStatus> TOMABLES = List.of(
            PublishJobStatus.QUEUED, PublishJobStatus.RETRYING, PublishJobStatus.DEFERRED);

    /** Los que ya les toca, primero por prioridad y luego por antigüedad. */
    @Query("""
            select j.id from PublishJob j
            where j.status in :estados and j.runAt <= :ahora
            order by j.priority asc, j.runAt asc
            """)
    List<UUID> findTomables(
            @Param("estados") List<PublishJobStatus> estados,
            @Param("ahora") LocalDateTime ahora,
            Limit tope);

    /**
     * Reclama el trabajo para este worker, si nadie se lo llevó antes.
     *
     * <p>Devuelve 1 si se lo quedó y 0 si otro llegó primero. Esa condición
     * de estado dentro del update es toda la exclusión mutua que hace falta:
     * la base serializa las dos escrituras y solo una ve el estado tomable.
     * Sin ella, dos instancias del API leyendo la misma lista publicarían el
     * mismo post dos veces, y una publicación duplicada en redes no se
     * deshace.
     *
     * <p>No usa SKIP LOCKED porque no todos los motores donde corre esto lo
     * soportan igual; un update condicional es portable y aquí basta.
     */
    @Modifying
    @Query("""
            update PublishJob j
            set j.status = com.metricol.api.enums.PublishJobStatus.RUNNING,
                j.lockedBy = :worker,
                j.lockedAt = :ahora,
                j.startedAt = coalesce(j.startedAt, :ahora),
                j.attempts = j.attempts + 1
            where j.id = :id and j.status in :estados
            """)
    int reclamar(
            @Param("id") UUID id,
            @Param("worker") String worker,
            @Param("ahora") LocalDateTime ahora,
            @Param("estados") List<PublishJobStatus> estados);

    /**
     * Devuelve a la cola los trabajos que un worker se llevó y nunca terminó
     * porque el proceso se murió a mitad. Sin esto se quedarían RUNNING para
     * siempre y esa publicación no volvería a intentarse nunca.
     */
    @Modifying
    @Query("""
            update PublishJob j
            set j.status = com.metricol.api.enums.PublishJobStatus.QUEUED,
                j.lockedBy = null,
                j.lockedAt = null,
                j.runAt = :ahora,
                j.lastError = :motivo
            where j.status = com.metricol.api.enums.PublishJobStatus.RUNNING
              and j.lockedAt < :limite
            """)
    int liberarAbandonados(
            @Param("limite") LocalDateTime limite,
            @Param("ahora") LocalDateTime ahora,
            @Param("motivo") String motivo);

    /** Cancela lo que quedara pendiente de un post que se borró o se editó. */
    @Modifying
    @Query("""
            update PublishJob j
            set j.status = com.metricol.api.enums.PublishJobStatus.CANCELED,
                j.finishedAt = :ahora
            where j.postId = :postId and j.status in :estados
            """)
    int cancelarDePost(
            @Param("postId") UUID postId,
            @Param("estados") List<PublishJobStatus> estados,
            @Param("ahora") LocalDateTime ahora);

    boolean existsByPostIdAndStatusIn(UUID postId, List<PublishJobStatus> estados);

    long countByStatus(PublishJobStatus status);

    long countByWorkspaceIdAndStatusIn(UUID workspaceId, List<PublishJobStatus> estados);

    /**
     * El momento del más viejo que todavía no arranca. Es la métrica que dice
     * si la cola va al día: el número de pendientes sube y baja con el
     * tráfico, pero una antigüedad que crece significa que no se da abasto.
     */
    @Query("""
            select min(j.runAt) from PublishJob j
            where j.status in :estados and j.runAt <= :ahora
            """)
    LocalDateTime masAntiguoPendiente(
            @Param("estados") List<PublishJobStatus> estados,
            @Param("ahora") LocalDateTime ahora);

    List<PublishJob> findByPostIdOrderByCreatedAtDesc(UUID postId);
}
