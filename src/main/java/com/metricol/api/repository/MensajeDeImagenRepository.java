package com.metricol.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.MensajeDeImagen;

public interface MensajeDeImagenRepository extends JpaRepository<MensajeDeImagen, UUID> {

    List<MensajeDeImagen> findByHiloIdOrderByCreadoEnAsc(UUID hiloId);

    @Modifying
    @Transactional
    @Query(value = """
            delete from mensajes_de_imagen where hilo_id in
              (select id from hilos_de_imagen where workspace_id = :workspace)
            """, nativeQuery = true)
    int borrarDelEspacio(@Param("workspace") UUID workspace);
}
