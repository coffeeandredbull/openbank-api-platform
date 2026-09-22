package com.openbank.apimanagement.application;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateApplicationRequest(

        @Size(max = 255, message = "name must not exceed 255 characters")
        @Pattern(
                regexp = "\\S(?:.*\\S)?",
                message = "name must not be blank or contain leading or trailing whitespace"
        )
        String name,

        @Size(max = 2000, message = "description must not exceed 2000 characters")
        String description
) {
}