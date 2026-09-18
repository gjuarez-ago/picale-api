package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.Invitation;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /** Por la huella del token del enlace. Es como se acepta una invitación. */
    Optional<Invitation> findByTokenHash(String tokenHash);

    /**
     * Las que siguen esperando en una organización.
     *
     * <p>Las aceptadas no salen: esas ya son gente del equipo y se administran
     * en la lista de miembros.
     */
    @Query("""
            select i from Invitation i
            where i.organization.id = :organizationId
              and i.acceptedAt is null
              and i.revokedAt is null
            order by i.createdAt desc
            """)
    List<Invitation> findPendientes(@Param("organizationId") UUID organizationId);

    /** Para no mandar dos invitaciones vivas al mismo correo. */
    @Query("""
            select i from Invitation i
            where i.organization.id = :organizationId
              and lower(i.email) = lower(:email)
              and i.acceptedAt is null
              and i.revokedAt is null
            """)
    Optional<Invitation> findVigentePara(@Param("organizationId") UUID organizationId,
            @Param("email") String email);
}
