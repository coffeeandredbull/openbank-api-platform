package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.scope.ValidScopes;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record CreateCredentialRequest(

        @NotNull(message = "applicationId is required")
        Long applicationId,

        @Future(message = "expiresAt must be in the future")
        Instant expiresAt,

        @ValidScopes
        List<String> scopes
) {

    public CreateCredentialRequest(Long applicationId) {
        this(applicationId, null, null);
    }

    public CreateCredentialRequest(Long applicationId, Instant expiresAt) {
        this(applicationId, expiresAt, null);
    }
}
