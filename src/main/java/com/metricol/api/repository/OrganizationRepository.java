package com.metricol.api.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
