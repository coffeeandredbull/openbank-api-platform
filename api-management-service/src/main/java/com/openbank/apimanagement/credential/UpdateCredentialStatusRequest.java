package com.openbank.apimanagement.credential;

import jakarta.validation.constraints.NotNull;

public record UpdateCredentialStatusRequest(

        @NotNull(message = "status is required")
        CredentialStatus status
) {
}