package com.openbank.analytics.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Internal-service authentication configuration (Phase 23, Slice 4). The
 * shared token is read from the environment (never hardcoded, never logged)
 * and is used to authenticate internal service-to-service calls such as the
 * gateway's analytics delivery.
 */
@Component
public class InternalApiAuthProperties {

    /**
     * Header name carrying the shared internal-service token on requests
     * destined for {@code /internal/**} endpoints.
     */
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final String analyticsToken;

    public InternalApiAuthProperties(
            @Value("${app.internal.analytics-token:}") String analyticsToken) {
        this.analyticsToken = analyticsToken == null ? "" : analyticsToken;
    }

    public String analyticsToken() {
        return analyticsToken;
    }
}