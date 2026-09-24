package com.openbank.apimanagement.subscription;

import java.time.Instant;

public record SubscriptionResponse(
        Long id,
        Long applicationId,
        Long apiVersionId,
        Long tierId,
        String tierName,
        SubscriptionStatus status,
        Instant revokedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static SubscriptionResponse from(Subscription subscription) {
        return new SubscriptionResponse(
                subscription.getId(),
                subscription.getApplication().getId(),
                subscription.getApiVersion().getId(),
                subscription.getTier().getId(),
                subscription.getTier().getName(),
                subscription.getStatus(),
                subscription.getRevokedAt(),
                subscription.getCreatedAt(),
                subscription.getUpdatedAt()
        );
    }
}