package com.metricol.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metricol.api.entity.SocialAccount;
import com.metricol.api.enums.SocialAccountStatus;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, UUID> {

    List<SocialAccount> findAllByOrderByConnectedAtDesc();

    long countByStatus(SocialAccountStatus status);
}
