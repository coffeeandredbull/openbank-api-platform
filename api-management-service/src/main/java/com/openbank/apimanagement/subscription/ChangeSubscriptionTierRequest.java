package com.openbank.apimanagement.subscription;

import jakarta.validation.constraints.NotNull;

public record ChangeSubscriptionTierRequest(

        @NotNull(message = "tierId is required")
        Long tierId
) {
}
