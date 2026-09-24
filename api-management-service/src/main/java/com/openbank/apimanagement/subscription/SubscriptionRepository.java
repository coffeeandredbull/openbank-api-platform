package com.openbank.apimanagement.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByIdAndApplication_OwnerUserId(Long id, Long ownerUserId);

    List<Subscription> findByApplication_OwnerUserIdOrderByIdAsc(Long ownerUserId);

    boolean existsByApplicationIdAndApiVersionId(Long applicationId, Long apiVersionId);

    boolean existsByApiVersionIdAndApplication_OwnerUserIdAndStatus(
            Long apiVersionId, Long ownerUserId, SubscriptionStatus status);

    boolean existsByApiVersionIdAndApplicationIdAndStatus(
            Long apiVersionId, Long applicationId, SubscriptionStatus status);
}