package com.openbank.apimanagement.credential;

import jakarta.validation.constraints.NotNull;

public record CreateCredentialRequest(

        @NotNull(message = "applicationId is required")
        Long applicationId
) {
}