package com.openbank.analytics.internal;

import com.openbank.analytics.event.AuthenticationType;
import com.openbank.analytics.event.RuntimeAnalyticsEvent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.Arrays;

/**
 * Wire DTO for the internal analytics ingestion endpoint (Phase 23, Slice 4).
 * It is the API boundary between the gateway's best-effort delivery and the
 * analytics service, and it deliberately exposes only the analytics-relevant
 * fields of a captured event — no credentials, passwords, hashes, JWTs, client
 * secrets, Authorization headers, or request/response bodies can be expressed
 * by this type. A request is only valid when all required fields are present
 * and within range; {@link #toEvent()} maps it onto the domain event for
 * persistence.
 */
public record RuntimeAnalyticsEventRequest(
        @NotNull
        Instant timestamp,

        @NotBlank
        String apiContext,

        @NotBlank
        String apiVersion,

        @NotBlank
        String httpMethod,

        @Min(100)
        @Max(599)
        int statusCode,

        @PositiveOrZero
        long latencyMs,

        @NotNull
        AuthenticationType authenticationType,

        Long userId,

        Long applicationId) {

    /**
     * Maps this validated request onto the domain {@link RuntimeAnalyticsEvent}.
     * Only standard HTTP methods are accepted; anything else (including custom
     * methods {@link HttpMethod#valueOf(String)} synthesizes for arbitrary
     * names) is rejected as an invalid argument (HTTP 400).
     */
    public RuntimeAnalyticsEvent toEvent() {
        HttpMethod method = HttpMethod.valueOf(httpMethod);
        if (!Arrays.asList(HttpMethod.values()).contains(method)) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + httpMethod);
        }
        return new RuntimeAnalyticsEvent(
                timestamp, apiContext, apiVersion, method, statusCode, latencyMs,
                authenticationType, userId, applicationId);
    }
}