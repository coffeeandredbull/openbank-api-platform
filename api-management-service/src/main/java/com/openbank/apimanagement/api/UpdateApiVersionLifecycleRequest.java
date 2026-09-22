package com.openbank.apimanagement.api;

import jakarta.validation.constraints.NotNull;

public record UpdateApiVersionLifecycleRequest(

        @NotNull(message = "lifecycle is required")
        ApiVersionLifecycle lifecycle
) {
}