package com.openbank.apimanagement.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateApiVersionRequest(

        @NotBlank(message = "version is required")
        @Size(max = 64, message = "version must not exceed 64 characters")
        @Pattern(
                regexp = "\\S(?:.*\\S)?",
                message = "version must not contain leading or trailing whitespace"
        )
        String version
) {
}