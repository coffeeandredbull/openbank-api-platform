package com.openbank.gateway.analytics;

import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable in-memory representation of one successful runtime API request
 * lifecycle (Phase 23). It is a domain/analytics event only: it is not
 * persisted, published, or collected anywhere yet, and it deliberately carries
 * no sensitive material — no credentials, passwords, hashes, JWTs,
 * Authorization headers, or request/response bodies.
 *
 * <p>Identity semantics depend on {@link #authenticationType()}:
 * <ul>
 *   <li>{@code JWT} — {@code userId} is the authenticated user id and
 *       {@code applicationId} is {@code null};</li>
 *   <li>{@code CLIENT_CREDENTIAL} — {@code userId} is the credential owner's
 *       user id and {@code applicationId} the authenticated application id.</li>
 * </ul>
 *
 * @param timestamp          when the request lifecycle was recorded
 * @param apiContext         the consumed API context path, e.g. {@code /payments}
 * @param apiVersion         the consumed API version, e.g. {@code v1}
 * @param httpMethod         the request HTTP method
 * @param statusCode         the response status code (100–599)
 * @param latencyMs          request latency in milliseconds (>= 0)
 * @param authenticationType the runtime authentication flow used
 * @param userId             the authenticated user id, or {@code null}
 * @param applicationId      the authenticated application id, or {@code null}
 */
public record RuntimeAnalyticsEvent(
        Instant timestamp,
        String apiContext,
        String apiVersion,
        HttpMethod httpMethod,
        int statusCode,
        long latencyMs,
        AuthenticationType authenticationType,
        Long userId,
        Long applicationId) {

    public RuntimeAnalyticsEvent {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(httpMethod, "httpMethod must not be null");
        Objects.requireNonNull(authenticationType, "authenticationType must not be null");
        if (apiContext == null || apiContext.isBlank()) {
            throw new IllegalArgumentException("apiContext must not be blank");
        }
        if (apiVersion == null || apiVersion.isBlank()) {
            throw new IllegalArgumentException("apiVersion must not be blank");
        }
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be between 100 and 599");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }
}