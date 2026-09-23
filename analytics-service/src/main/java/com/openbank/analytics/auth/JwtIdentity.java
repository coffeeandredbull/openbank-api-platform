package com.openbank.analytics.auth;

/**
 * The caller identity extracted from a verified JWT (Phase 23, Slice 5). The
 * role is {@code null} when the token declares a role this platform does not
 * recognize: the caller is still authenticated (the JWT is genuinely signed
 * and unexpired) but holds no authorities, so authorization rejects them.
 */
public record JwtIdentity(
        Long userId,
        UserRole role
) {
}