package com.openbank.apimanagement.subscription;

import jakarta.validation.constraints.NotNull;

public record CreateSubscriptionRequest(

        @NotNull(message = "applicationId is required")
        Long applicationId,

        @NotNull(message = "apiVersionId is required")
        Long apiVersionId,

        @NotNull(message = "tierId is required")
        Long tierId
) {
}