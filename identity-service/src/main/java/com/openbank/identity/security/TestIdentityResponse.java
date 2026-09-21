package com.openbank.identity.security;

import com.openbank.identity.auth.JwtIdentity;
import com.openbank.identity.user.UserRole;

public record TestIdentityResponse(
        Long userId,
        UserRole role
) {

    public static TestIdentityResponse from(JwtIdentity identity) {
        return new TestIdentityResponse(identity.userId(), identity.role());
    }
}