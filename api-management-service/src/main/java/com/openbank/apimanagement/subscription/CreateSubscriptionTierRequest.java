package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.scope.ValidScopes;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateSubscriptionTierRequest(

        @NotNull(message = "name is required")
        @Size(max = 100, message = "name must not exceed 100 characters")
        @Pattern(
                regexp = "\\S(?:.*\\S)?",
                message = "name must not be blank or contain leading or trailing whitespace"
        )
        String name,

        @Size(max = 500, message = "description must not exceed 500 characters")
        String description,

        @Positive(message = "requestsPerWindow must be greater than 0")
        Integer requestsPerWindow,

        @Positive(message = "windowSeconds must be greater than 0")
        Integer windowSeconds,

        @PositiveOrZero(message = "priceCentsPerMonth must not be negative")
        Integer priceCentsPerMonth,

        @ValidScopes
        List<String> allowedScopes
) {

    public CreateSubscriptionTierRequest(
            String name, String description, Integer requestsPerWindow, Integer windowSeconds,
            Integer priceCentsPerMonth) {
        this(name, description, requestsPerWindow, windowSeconds, priceCentsPerMonth, null);
    }
}
