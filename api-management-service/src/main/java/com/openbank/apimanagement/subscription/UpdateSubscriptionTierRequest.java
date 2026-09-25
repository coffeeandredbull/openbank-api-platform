package com.openbank.apimanagement.subscription;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@AtLeastOneFieldPresent
public record UpdateSubscriptionTierRequest(

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
        Integer windowSeconds
) {
}
