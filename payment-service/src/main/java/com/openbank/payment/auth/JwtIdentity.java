package com.openbank.payment.auth;

public record JwtIdentity(
        Long userId,
        UserRole role
) {
}