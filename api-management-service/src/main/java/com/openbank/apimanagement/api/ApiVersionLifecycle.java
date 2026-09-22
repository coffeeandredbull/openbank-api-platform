package com.openbank.apimanagement.api;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public enum ApiVersionLifecycle {
    CREATED,
    PUBLISHED,
    DEPRECATED,
    RETIRED;

    private static final Map<ApiVersionLifecycle, Set<ApiVersionLifecycle>> ALLOWED_TRANSITIONS =
            new EnumMap<>(ApiVersionLifecycle.class);

    static {
        ALLOWED_TRANSITIONS.put(CREATED, Set.of(PUBLISHED, RETIRED));
        ALLOWED_TRANSITIONS.put(PUBLISHED, Set.of(DEPRECATED));
        ALLOWED_TRANSITIONS.put(DEPRECATED, Set.of(RETIRED));
        ALLOWED_TRANSITIONS.put(RETIRED, Set.of());
    }

    public boolean canTransitionTo(ApiVersionLifecycle target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }
}