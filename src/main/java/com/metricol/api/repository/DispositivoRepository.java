package com.metricol.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Dispositivo;

public interface DispositivoRepository extends JpaRepository<Dispositivo, UUID> {

    Optional<Dispositivo> findByToken(String token);

    List<Dispositivo> findByUserIdIn(Collection<UUID> userIds);

    @Modifying
    @Transactional
    long deleteByToken(String token);
}
