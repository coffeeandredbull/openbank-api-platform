package com.openbank.apimanagement.credential;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record UpdateCredentialExpirationRequest(

        @NotNull(message = "expiresAt is required")
        @Future(message = "expiresAt must be in the future")
        Instant expiresAt
) {
}
