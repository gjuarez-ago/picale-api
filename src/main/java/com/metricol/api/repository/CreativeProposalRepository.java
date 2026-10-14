package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.CreativeProposal;

public interface CreativeProposalRepository extends JpaRepository<CreativeProposal, UUID> {
    List<CreativeProposal> findByJobIdOrderByPositionAsc(UUID jobId);
    Optional<CreativeProposal> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    boolean existsByJobId(UUID jobId);
}
