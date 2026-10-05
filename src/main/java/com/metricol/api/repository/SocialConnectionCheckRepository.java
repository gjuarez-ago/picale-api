package com.metricol.api.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.SocialConnectionCheck;

/**
 * No hace falta filtrar por workspace a mano: {@code SocialConnectionCheck}
 * lleva {@code @TenantId}, así que Hibernate agrega el filtro solo (ver
 * {@link com.metricol.api.config.TenantIdentifierResolver}).
 */
public interface SocialConnectionCheckRepository extends JpaRepository<SocialConnectionCheck, UUID> {

    Optional<SocialConnectionCheck> findByPlatform(String platform);

    /** Los espacios con alguna red por reconectar, de todos los clientes: lo recorre el recordatorio. */
    @org.springframework.data.jpa.repository.Query(value = """
            select distinct tenant_id from social_connection_checks
            where fallo_por_conexion_en is not null or expired_since is not null
            """, nativeQuery = true)
    java.util.List<String> espaciosPorReconectar();
}
