package com.openbank.identity.auth;

import com.openbank.identity.user.User;
import com.openbank.identity.user.UserRole;

public record LoginResponse(
        Long userId,
        String email,
        UserRole role
) {

    public static LoginResponse from(User user) {
        return new LoginResponse(user.getId(), user.getEmail(), user.getRole());
    }
}