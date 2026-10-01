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

    /**
     * Lo publicado a lo que le toca leer cómo le fue, de TODOS los workspaces:
     * [id del destino, red, id en la red, perfil de upload-post].
     *
     * <p>Las primeras 48 horas cada 12 (es cuando más se mueve); después cada
     * dos días, hasta los 14 días, cuando ya casi no cambia. Primero lo que
     * nunca se leyó. En SQL nativo por lo de siempre: el worker no tiene tenant.
     */
    @Query(value = """
            select cast(pt.id as varchar), sa.platform, pt.external_post_id,
                   coalesce(nullif(w.upload_post_profile, ''), p.tenant_id)
            from post_targets pt
            join posts p on p.id = pt.post_id
            join social_accounts sa on sa.id = pt.social_account_id
            left join workspaces w on cast(w.id as varchar) = p.tenant_id
            where pt.status = 'PUBLISHED'
              and pt.external_post_id is not null and pt.external_post_id <> ''
              and pt.published_at between :desde and :hasta
              and (pt.metricas_en is null
                   or (pt.published_at > :recientes and pt.metricas_en < :hace12h)
                   or pt.metricas_en < :hace2d)
            order by pt.metricas_en asc nulls first, pt.published_at desc
            limit :tope
            """, nativeQuery = true)
    List<Object[]> porMedir(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta,
            @Param("recientes") LocalDateTime recientes, @Param("hace12h") LocalDateTime hace12h,
            @Param("hace2d") LocalDateTime hace2d, @Param("tope") int tope);

    /**
     * Lo medido de un workspace desde {@code desde}, para aprender de ello:
     * [publicada, texto enviado, vistas, alcance, me gusta, comentarios,
     * compartidos, guardados, enlace, red].
     */
    @Query(value = """
            select pt.published_at, coalesce(pt.caption_enviado, pt.caption, p.caption),
                   pt.vistas, pt.alcance, pt.me_gusta, pt.comentarios, pt.compartidos, pt.guardados,
                   pt.external_url, sa.platform
            from post_targets pt
            join posts p on p.id = pt.post_id
            join social_accounts sa on sa.id = pt.social_account_id
            where p.tenant_id = :tenant
              and pt.metricas_en is not null
              and coalesce(pt.vistas, pt.alcance, pt.me_gusta, pt.comentarios, pt.compartidos, pt.guardados) is not null
              and pt.published_at >= :desde
            """, nativeQuery = true)
    List<Object[]> medidasDe(@Param("tenant") String tenant, @Param("desde") LocalDateTime desde);
}
