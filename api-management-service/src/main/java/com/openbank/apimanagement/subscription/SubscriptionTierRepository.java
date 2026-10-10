package com.openbank.apimanagement.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionTierRepository extends JpaRepository<SubscriptionTier, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<SubscriptionTier> findAllByOrderByIdAsc();
}