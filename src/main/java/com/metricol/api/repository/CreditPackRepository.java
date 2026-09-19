package com.metricol.api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.CreditPack;

public interface CreditPackRepository extends JpaRepository<CreditPack, UUID> {

    Optional<CreditPack> findByCode(String code);

    List<CreditPack> findAllByOrderBySortOrderAscCreditsAsc();
}
