package com.openbank.apimanagement.subscription;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public enum SubscriptionStatus {
    PENDING,
    ACTIVE,
    DENIED,
    REVOKED;

    private static final Map<SubscriptionStatus, Set<SubscriptionStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(SubscriptionStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(PENDING, Set.of(ACTIVE, DENIED, REVOKED));
        ALLOWED_TRANSITIONS.put(ACTIVE, Set.of(REVOKED));
        ALLOWED_TRANSITIONS.put(DENIED, Set.of(ACTIVE, REVOKED));
        ALLOWED_TRANSITIONS.put(REVOKED, Set.of());
    }

    public boolean canTransitionTo(SubscriptionStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }
}