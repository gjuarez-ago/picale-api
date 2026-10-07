package com.metricol.api.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Comentario;
import com.metricol.api.enums.Platform;

public interface ComentarioRepository extends JpaRepository<Comentario, UUID> {

    Optional<Comentario> findByRedAndIdEnLaRed(Platform red, String idEnLaRed);

    /**
     * Los que ya tenemos de esos identificadores. Se consulta de golpe antes de
     * guardar una página entera: una consulta por comentario sería una vuelta
     * de cien consultas para no guardar nada.
     *
     * <p>Sin tenant: lo pide el worker, que no tiene usuario, y el filtro de
     * Hibernate no se aplicaría. El par (red, id) es único en toda la tabla, así
     * que no hay forma de que devuelva algo de otro inquilino que no sea
     * exactamente el mismo comentario.
     */
    @Query(value = "select id_en_la_red from comentarios where red = :red and id_en_la_red in (:ids)",
            nativeQuery = true)
    List<String> cualesYaEstan(@Param("red") String red, @Param("ids") List<String> ids);

    /** La bandeja: lo pendiente de un espacio, lo más nuevo arriba. */
    @Query("""
            select c from Comentario c
            where c.workspaceId = :workspace and c.propio = false and c.ocultoEn is null
              and (:soloPendientes = false or c.atendidoEn is null)
              and (:red is null or c.red = :red)
              and (:texto is null or lower(c.texto) like :texto or lower(c.autorNombre) like :texto)
            order by c.escritoEn desc nulls last
            """)
    Page<Comentario> bandeja(@Param("workspace") UUID workspace, @Param("soloPendientes") boolean soloPendientes,
            @Param("red") Platform red, @Param("texto") String texto, Pageable pagina);

    @Query("""
            select count(c) from Comentario c
            where c.workspaceId = :workspace and c.atendidoEn is null and c.propio = false and c.ocultoEn is null
            """)
    long pendientesDe(@Param("workspace") UUID workspace);

    /** Pendientes por red, para los contadores de los filtros: [red, cuántos]. */
    @Query("""
            select c.red, count(c) from Comentario c
            where c.workspaceId = :workspace and c.atendidoEn is null and c.propio = false and c.ocultoEn is null
            group by c.red
            """)
    List<Object[]> pendientesPorRed(@Param("workspace") UUID workspace);

    /** El hilo de un comentario: él y lo que le contestaron. */
    @Query("""
            select c from Comentario c
            where c.workspaceId = :workspace and (c.idEnLaRed = :id or c.padreIdEnLaRed = :id)
            order by c.escritoEn asc nulls last
            """)
    List<Comentario> hilo(@Param("workspace") UUID workspace, @Param("id") String idEnLaRed);

    /**
     * Lo que entró desde {@code desde} y sigue sin atender, por espacio:
     * [workspace_id, cuántos, cuántas cuentas]. Es lo que decide el aviso al
     * teléfono. Nativa y sin tenant porque la hace el worker.
     */
    @Query(value = """
            select cast(workspace_id as varchar), count(*), count(distinct social_account_id)
            from comentarios
            where traido_en >= :desde and atendido_en is null and propio = false and oculto_en is null
            group by workspace_id
            """, nativeQuery = true)
    List<Object[]> nuevosSinAtenderPorEspacio(@Param("desde") LocalDateTime desde);

    @Modifying
    @Transactional
    @Query("update Comentario c set c.leidoEn = :en where c.id in :ids and c.leidoEn is null")
    int marcarLeidos(@Param("ids") List<UUID> ids, @Param("en") LocalDateTime en);

    /** Al eliminar un espacio para siempre, sus comentarios se van con él. */
    @Modifying
    @Transactional
    @Query(value = "delete from comentarios where workspace_id = :workspace", nativeQuery = true)
    int borrarDelEspacio(@Param("workspace") UUID workspace);

    /** Al eliminar a una persona: lo atendido queda, pero sin apuntar a quién. */
    @Modifying
    @Transactional
    @Query(value = "update comentarios set atendido_por = null where atendido_por = :persona", nativeQuery = true)
    int olvidarAQuienAtendio(@Param("persona") UUID persona);
}
