package com.openbank.apimanagement.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionTierRepository extends JpaRepository<SubscriptionTier, Long> {

    boolean existsByName(String name);
}