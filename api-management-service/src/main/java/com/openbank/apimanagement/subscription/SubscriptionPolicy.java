package com.openbank.apimanagement.subscription;

public record SubscriptionPolicy(
        Long tierId,
        String tierName,
        int requestsPerWindow,
        int windowSeconds,
        String allowedScopes
) {

    public static SubscriptionPolicy from(Subscription subscription) {
        return new SubscriptionPolicy(
                subscription.getTier().getId(),
                subscription.getTier().getName(),
                subscription.getTier().getRequestsPerWindow(),
                subscription.getTier().getWindowSeconds(),
                subscription.getTier().getAllowedScopes()
        );
    }
}
