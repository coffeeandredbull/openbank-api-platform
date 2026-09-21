package com.openbank.identity.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateUserRequest(

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        String email,

        @NotBlank(message = "passwordHash is required")
        String passwordHash,

        @NotNull(message = "role is required")
        UserRole role
) {
}