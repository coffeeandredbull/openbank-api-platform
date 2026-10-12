package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.scope.ValidScopes;
import jakarta.validation.constraints.Future;

import java.time.Instant;
import java.util.List;

public record CreateApplicationCredentialRequest(

        @Future(message = "expiresAt must be in the future")
        Instant expiresAt,

        @ValidScopes
        List<String> scopes
) {
}
