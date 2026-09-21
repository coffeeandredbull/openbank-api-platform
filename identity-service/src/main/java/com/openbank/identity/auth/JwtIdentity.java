package com.openbank.identity.auth;

import com.openbank.identity.user.UserRole;

public record JwtIdentity(
        Long userId,
        UserRole role
) {
}