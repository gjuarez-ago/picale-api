package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.HiloDeImagen;

public interface HiloDeImagenRepository extends JpaRepository<HiloDeImagen, UUID> {

    /** El hilo abierto más reciente de esa persona en ese espacio, si lo hay. */
    @Query("""
            select h from HiloDeImagen h
            where h.workspaceId = :workspace and h.userId = :user and h.cerradoEn is null
            order by h.actualizadoEn desc
            """)
    List<HiloDeImagen> abiertosDe(@Param("workspace") UUID workspace, @Param("user") UUID user);

    default Optional<HiloDeImagen> ultimoAbierto(UUID workspace, UUID user) {
        return abiertosDe(workspace, user).stream().findFirst();
    }

    /** Al eliminar un espacio para siempre, sus conversaciones se van con él. */
    @Modifying
    @Transactional
    @Query(value = "delete from hilos_de_imagen where workspace_id = :workspace", nativeQuery = true)
    int borrarDelEspacio(@Param("workspace") UUID workspace);
}
