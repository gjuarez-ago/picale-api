package com.metricol.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metricol.api.entity.Workspace;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    /** Los espacios de una organización. Los archivados salen al final. */
    @Query("""
            select w from Workspace w
            where w.organization.id = :organizationId
            order by case when w.archivedAt is null then 0 else 1 end, w.createdAt
            """)
    List<Workspace> findDeLaOrganizacion(@Param("organizationId") UUID organizationId);

    /** Cuántos espacios activos tiene. Es lo que se compara con su tope. */
    long countByOrganizationIdAndArchivedAtIsNull(UUID organizationId);

    /** Lo creado antes de que existieran las organizaciones. Ver OrganizationService. */
    @Query("select w from Workspace w where w.organization is null")
    List<Workspace> findSinOrganizacion();
}
