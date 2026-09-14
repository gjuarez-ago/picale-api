package com.metricol.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.AppLimit;

public interface AppLimitRepository extends JpaRepository<AppLimit, String> {
}
