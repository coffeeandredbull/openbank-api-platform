package com.openbank.apimanagement.subscription;

import jakarta.validation.constraints.NotNull;

public record UpdateSubscriptionTierStatusRequest(

        @NotNull(message = "status is required")
        SubscriptionTierStatus status
) {
}
