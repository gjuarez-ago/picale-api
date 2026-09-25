package com.metricol.api.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.PostTarget;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.PostTargetStatus;

public interface PostTargetRepository extends JpaRepository<PostTarget, UUID> {

    /**
     * Cuántas salieron de verdad en esa red desde {@code desde}.
     *
     * <p>Es la ventana móvil de Meta: 25 en 24 horas corridas, no por día
     * natural. El tenant va explícito en vez de confiar en el filtro de
     * Hibernate porque esta consulta la hace también el worker, que no tiene
     * usuario, y un filtro que no se aplica devuelve el total de todos.
     */
    @Query("""
            select count(t) from PostTarget t
            join t.post p
            join t.socialAccount a
            where p.tenantId = :tenant
              and a.platform = :platform
              and t.status = com.metricol.api.enums.PostTargetStatus.PUBLISHED
              and t.publishedAt >= :desde
            """)
    long countPublicadasDesde(
            @Param("tenant") String tenant,
            @Param("platform") Platform platform,
            @Param("desde") LocalDateTime desde);

    /**
     * Cuántas publicaciones de ese workspace ya tienen comprometido un hueco
     * de esa red para un día: las que están en cola o programadas para salir
     * entre {@code desde} y {@code hasta}, sin contar la que se está editando.
     *
     * <p>Es lo que permite decir «no» al programar y no al publicar: lo que
     * está esperando salir ese día también cuenta, aunque todavía no haya
     * tocado el contador.
     */
    @Query("""
            select count(t) from PostTarget t
            join t.post p
            join t.socialAccount a
            where p.tenantId = :tenant
              and a.platform = :platform
              and p.status in :estadosPost
              and t.status in :estadosDestino
              and coalesce(p.scheduledAt, p.createdAt) >= :desde
              and coalesce(p.scheduledAt, p.createdAt) < :hasta
              and p.id <> :excluir
              and p.deletedAt is null
            """)
    long countComprometidas(
            @Param("tenant") String tenant,
            @Param("platform") Platform platform,
            @Param("estadosPost") List<PostStatus> estadosPost,
            @Param("estadosDestino") List<PostTargetStatus> estadosDestino,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta,
            @Param("excluir") UUID excluir);
}
