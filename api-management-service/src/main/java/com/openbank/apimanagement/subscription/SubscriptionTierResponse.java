package com.openbank.apimanagement.subscription;

import java.time.Instant;

public record SubscriptionTierResponse(
        Long id,
        String name,
        String description,
        int requestsPerWindow,
        int windowSeconds,
        int priceCentsPerMonth,
        String allowedScopes,
        SubscriptionTierStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public SubscriptionTierResponse(
            Long id, String name, String description, int requestsPerWindow, int windowSeconds,
            int priceCentsPerMonth, SubscriptionTierStatus status, Instant createdAt, Instant updatedAt) {
        this(id, name, description, requestsPerWindow, windowSeconds, priceCentsPerMonth, null, status,
                createdAt, updatedAt);
    }

    public static SubscriptionTierResponse from(SubscriptionTier tier) {
        return new SubscriptionTierResponse(
                tier.getId(),
                tier.getName(),
                tier.getDescription(),
                tier.getRequestsPerWindow(),
                tier.getWindowSeconds(),
                tier.getPriceCentsPerMonth(),
                tier.getAllowedScopes(),
                tier.getStatus(),
                tier.getCreatedAt(),
                tier.getUpdatedAt()
        );
    }
}
