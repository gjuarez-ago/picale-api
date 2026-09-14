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
}
