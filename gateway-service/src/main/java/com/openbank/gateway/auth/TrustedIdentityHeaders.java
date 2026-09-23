package com.openbank.gateway.auth;

import java.util.List;

/**
 * Gateway-generated headers that will carry verified caller identity to managed
 * APIs (Phase 22). These values are produced by the gateway after successful
 * authentication and must never be accepted from clients — the gateway strips
 * any client-supplied values before trusted ones are added.
 */
public final class TrustedIdentityHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String ROLES = "X-Roles";
    public static final String APPLICATION_ID = "X-Application-Id";
    public static final String CLIENT_ID = "X-Client-Id";

    private static final List<String> ALL_HEADER_NAMES =
            List.of(USER_ID, ROLES, APPLICATION_ID, CLIENT_ID);

    private TrustedIdentityHeaders() {
    }

    /**
     * All trusted identity header names, in a reusable, immutable list. Client
     * presence of any of these names must be neutralized before injecting
     * gateway-derived values.
     */
    public static List<String> allHeaderNames() {
        return ALL_HEADER_NAMES;
    }
}