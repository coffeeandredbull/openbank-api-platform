package com.openbank.apimanagement.subscription;

import java.time.Instant;

public record SubscriptionResponse(
        Long id,
        Long applicationId,
        Long apiVersionId,
        Long tierId,
        String tierName,
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
                subscription.getCreatedAt(),
                subscription.getUpdatedAt()
        );
    }
}