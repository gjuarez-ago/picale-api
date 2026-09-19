package com.metricol.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.StripeEvent;

public interface StripeEventRepository extends JpaRepository<StripeEvent, String> {
}
