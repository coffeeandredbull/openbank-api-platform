package com.openbank.apimanagement.credential;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateCredentialRequest(

        @NotNull(message = "applicationId is required")
        Long applicationId,

        @Future(message = "expiresAt must be in the future")
        Instant expiresAt
) {

    public CreateCredentialRequest(Long applicationId) {
        this(applicationId, null);
    }
}