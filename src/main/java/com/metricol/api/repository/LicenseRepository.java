package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.metricol.api.entity.License;
import com.metricol.api.entity.Workspace;

public interface LicenseRepository extends JpaRepository<License, UUID> {

    Optional<License> findByWorkspaceId(UUID workspaceId);

    Optional<License> findByStripeSubscriptionId(String stripeSubscriptionId);

    List<License> findByOrganizationId(UUID organizationId);

    /** Los workspaces que existen y todavía no tienen licencia: los de antes de encender los cobros. */
    @Query("select w from Workspace w where w.organization is not null "
            + "and not exists (select 1 from License l where l.workspaceId = w.id)")
    List<Workspace> workspacesSinLicencia();
}
