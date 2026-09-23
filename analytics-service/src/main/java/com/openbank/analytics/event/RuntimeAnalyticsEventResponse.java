package com.openbank.analytics.event;

import java.time.Instant;

/**
 * Response DTO for one analytics event (Phase 23, Slice 5). It deliberately
 * exposes only analytics-relevant fields and can never carry credentials,
 * passwords, hashes, JWTs, Authorization headers, internal service tokens, or
 * request/response bodies. The persistence entity is never serialized.
 */
public record RuntimeAnalyticsEventResponse(
        Long id,
        Instant timestamp,
        String apiContext,
        String apiVersion,
        String httpMethod,
        int statusCode,
        long latencyMs,
        AuthenticationType authenticationType,
        Long userId,
        Long applicationId) {

    public static RuntimeAnalyticsEventResponse from(RuntimeAnalyticsEventEntity entity) {
        return new RuntimeAnalyticsEventResponse(
                entity.getId(),
                entity.getTimestamp(),
                entity.getApiContext(),
                entity.getApiVersion(),
                entity.getHttpMethod(),
                entity.getStatusCode(),
                entity.getLatencyMs(),
                entity.getAuthenticationType(),
                entity.getUserId(),
                entity.getApplicationId());
    }
}