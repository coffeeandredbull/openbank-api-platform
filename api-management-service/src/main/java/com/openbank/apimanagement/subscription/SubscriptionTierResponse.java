package com.openbank.apimanagement.subscription;

import java.time.Instant;

public record SubscriptionTierResponse(
        Long id,
        String name,
        String description,
        int requestsPerWindow,
        int windowSeconds,
        Instant createdAt,
        Instant updatedAt
) {

    public static SubscriptionTierResponse from(SubscriptionTier tier) {
        return new SubscriptionTierResponse(
                tier.getId(),
                tier.getName(),
                tier.getDescription(),
                tier.getRequestsPerWindow(),
                tier.getWindowSeconds(),
                tier.getCreatedAt(),
                tier.getUpdatedAt()
        );
    }
}
