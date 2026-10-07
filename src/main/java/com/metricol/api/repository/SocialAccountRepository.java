package com.metricol.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.SocialAccount;
import com.metricol.api.enums.SocialAccountStatus;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, UUID> {

    List<SocialAccount> findAllByOrderByConnectedAtDesc();

    long countByStatus(SocialAccountStatus status);

    /**
     * Apunta que esa cuenta hay que volver a conectarla para los comentarios.
     * Con un UPDATE de una columna: lo hace el worker, que no tiene tenant, y
     * un {@code save} de la entidad reescribiría la fila entera.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            value = "update social_accounts set comentarios_bloqueado_en = :en where id = :id",
            nativeQuery = true)
    int marcarComentariosBloqueados(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("en") java.time.LocalDateTime en);

    /** Las que esperan reconexión para comentarios, en ese espacio: [red]. */
    @org.springframework.data.jpa.repository.Query(
            value = "select distinct platform from social_accounts where tenant_id = :tenant "
                    + "and comentarios_bloqueado_en is not null and status = 'CONNECTED'",
            nativeQuery = true)
    List<String> redesPorReconectarParaComentarios(
            @org.springframework.data.repository.query.Param("tenant") String tenant);
}
