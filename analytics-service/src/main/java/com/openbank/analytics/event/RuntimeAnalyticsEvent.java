package com.openbank.analytics.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpMethod;

import java.time.Instant;

public record RuntimeAnalyticsEvent(
        @NotNull
        Instant timestamp,

        @NotBlank
        String apiContext,

        @NotBlank
        String apiVersion,

        @NotNull
        HttpMethod httpMethod,

        @Min(100)
        @Max(599)
        int statusCode,

        @PositiveOrZero
        long latencyMs,

        @NotNull
        AuthenticationType authenticationType,

        Long userId,

        Long applicationId) {
}