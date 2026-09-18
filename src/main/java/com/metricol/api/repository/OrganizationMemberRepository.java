package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.OrganizationMember;
import com.metricol.api.enums.OrgRole;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    /** El papel de una persona en una organización. Vacío si no pertenece. */
    Optional<OrganizationMember> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    /** Las organizaciones de una persona. Hoy casi siempre una. */
    @Query("""
            select m from OrganizationMember m
            join fetch m.organization
            where m.user.id = :userId
            order by m.createdAt
            """)
    List<OrganizationMember> findDelUsuario(@Param("userId") UUID userId);

    /** El equipo de la organización, para la pantalla de administración. */
    @Query("""
            select m from OrganizationMember m
            join fetch m.user
            where m.organization.id = :organizationId
            order by m.createdAt
            """)
    List<OrganizationMember> findDeLaOrganizacion(@Param("organizationId") UUID organizationId);

    /**
     * Cuántos dueños quedan. Se pregunta antes de quitar a alguien o bajarle
     * el papel: una organización sin dueño no la recupera nadie desde la
     * aplicación.
     */
    long countByOrganizationIdAndRole(UUID organizationId, OrgRole role);
}
