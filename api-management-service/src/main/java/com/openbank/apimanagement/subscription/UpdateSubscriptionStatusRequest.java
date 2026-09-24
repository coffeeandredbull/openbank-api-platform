package com.openbank.apimanagement.subscription;

import jakarta.validation.constraints.NotNull;

public record UpdateSubscriptionStatusRequest(

        @NotNull(message = "status is required")
        SubscriptionStatus status
) {
}