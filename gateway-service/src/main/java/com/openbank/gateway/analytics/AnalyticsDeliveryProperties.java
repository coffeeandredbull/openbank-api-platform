package com.openbank.gateway.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Runtime configuration for the gateway's best-effort delivery of captured
 * analytics events to the analytics service (Phase 23, Slice 4). Values are
 * read directly from environment variables — there is no application.yml
 * section, mirroring {@code RateLimitProperties} and
 * {@code ManagedApiUpstreamProperties}. The internal token doubles as the
 * delivery switch: when it is blank, delivery is disabled entirely.
 */
@Component
public class AnalyticsDeliveryProperties {

    /**
     * Header carrying the shared internal-service token on requests sent to
     * the analytics service's {@code /internal/**} endpoints.
     */
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final String analyticsServiceUrl;
    private final String internalToken;
    private final long deliveryTimeoutMs;
    private final int queueCapacity;

    public AnalyticsDeliveryProperties(
            @Value("${ANALYTICS_SERVICE_URL:http://localhost:8083}") String analyticsServiceUrl,
            @Value("${ANALYTICS_INTERNAL_TOKEN:}") String internalToken,
            @Value("${ANALYTICS_DELIVERY_TIMEOUT_MS:500}") long deliveryTimeoutMs,
            @Value("${ANALYTICS_QUEUE_CAPACITY:1024}") int queueCapacity) {
        validateUrl(analyticsServiceUrl);
        if (deliveryTimeoutMs <= 0 || deliveryTimeoutMs > 60_000) {
            throw new IllegalStateException(
                    "ANALYTICS_DELIVERY_TIMEOUT_MS must be between 1 and 60000 ms, got "
                            + deliveryTimeoutMs);
        }
        if (queueCapacity <= 0) {
            throw new IllegalStateException(
                    "ANALYTICS_QUEUE_CAPACITY must be a positive integer, got " + queueCapacity);
        }
        this.analyticsServiceUrl = analyticsServiceUrl;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.deliveryTimeoutMs = deliveryTimeoutMs;
        this.queueCapacity = queueCapacity;
    }

    public String analyticsServiceUrl() {
        return analyticsServiceUrl;
    }

    public String internalToken() {
        return internalToken;
    }

    /**
     * Delivery is enabled only when an internal token is configured; without
     * it the worker never starts and events stay queued in the sink.
     */
    public boolean deliveryEnabled() {
        return !internalToken.isBlank();
    }

    public long deliveryTimeoutMs() {
        return deliveryTimeoutMs;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    private static void validateUrl(String value) {
        if (value == null || value.isBlank() || containsWhitespace(value)) {
            throw invalidConfiguration();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalidConfiguration();
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalidConfiguration();
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw invalidConfiguration();
        }
        if (uri.getUserInfo() != null) {
            throw invalidConfiguration();
        }
        if (uri.getFragment() != null) {
            throw invalidConfiguration();
        }
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException(
                "ANALYTICS_SERVICE_URL must be an absolute http(s) URL that has a host and contains "
                        + "no credentials, fragment, or whitespace; refusing to start");
    }
}