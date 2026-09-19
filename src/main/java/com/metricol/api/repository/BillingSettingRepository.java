package com.metricol.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.BillingSetting;

public interface BillingSettingRepository extends JpaRepository<BillingSetting, String> {
}
