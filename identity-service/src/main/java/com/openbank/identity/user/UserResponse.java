package com.openbank.identity.user;

import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        UserRole role,
        Instant createdAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}