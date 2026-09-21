package com.openbank.identity.auth;

import com.openbank.identity.user.User;
import com.openbank.identity.user.UserRole;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Long userId,
        String email,
        UserRole role
) {

    public static LoginResponse from(User user, String accessToken, long expiresIn) {
        return new LoginResponse(accessToken, "Bearer", expiresIn, user.getId(), user.getEmail(), user.getRole());
    }
}