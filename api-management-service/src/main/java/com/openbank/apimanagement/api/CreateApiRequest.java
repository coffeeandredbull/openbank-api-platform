package com.openbank.apimanagement.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateApiRequest(

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must not exceed 255 characters")
        String name,

        @NotBlank(message = "description is required")
        @Size(max = 2000, message = "description must not exceed 2000 characters")
        String description,

        @NotBlank(message = "contextPath is required")
        @Size(max = 255, message = "contextPath must not exceed 255 characters")
        @ValidContextPath
        String contextPath
) {
}