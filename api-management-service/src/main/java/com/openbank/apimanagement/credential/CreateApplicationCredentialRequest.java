package com.openbank.apimanagement.credential;

import jakarta.validation.constraints.Future;

import java.time.Instant;

public record CreateApplicationCredentialRequest(

        @Future(message = "expiresAt must be in the future")
        Instant expiresAt
) {
}
