package com.openbank.gateway.auth;

public record JwtIdentity(Long userId, UserRole role) {
}