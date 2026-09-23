package com.openbank.gateway.analytics;

import java.time.Instant;

/**
 * Wire form of a captured {@link RuntimeAnalyticsEvent} as delivered to the
 * analytics service (Phase 23, Slice 4). It is a deliberate, minimal wire
 * contract: the method and authentication type are serialized as plain strings
 * (no enum/object serialization surprises) and it carries exactly the same
 * non-sensitive fields as the captured event — never credentials, passwords,
 * hashes, JWTs, client secrets, Authorization headers, or request/response
 * bodies.
 */
public record AnalyticsEventDeliveryPayload(
        Instant timestamp,
        String apiContext,
        String apiVersion,
        String httpMethod,
        int statusCode,
        long latencyMs,
        String authenticationType,
        Long userId,
        Long applicationId) {

    public static AnalyticsEventDeliveryPayload from(RuntimeAnalyticsEvent event) {
        return new AnalyticsEventDeliveryPayload(
                event.timestamp(),
                event.apiContext(),
                event.apiVersion(),
                event.httpMethod().name(),
                event.statusCode(),
                event.latencyMs(),
                event.authenticationType().name(),
                event.userId(),
                event.applicationId());
    }
}