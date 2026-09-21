package com.openbank.apimanagement.auth;

public record JwtIdentity(
        Long userId,
        UserRole role
) {
}