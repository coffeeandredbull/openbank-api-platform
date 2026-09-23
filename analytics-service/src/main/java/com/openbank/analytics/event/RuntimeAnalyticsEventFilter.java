package com.openbank.analytics.event;

import java.time.Instant;

/**
 * Validated query parameters for {@code GET /analytics/events} (Phase 23,
 * Slice 5). All optional filters are exact matches; {@code from}/{@code to}
 * are inclusive {@link Instant} bounds. Validation fails fast through
 * {@link IllegalArgumentException}, which the centralized handler maps to
 * HTTP 400 without echoing values back.
 */
public record RuntimeAnalyticsEventFilter(
        String apiContext,
        String apiVersion,
        AuthenticationType authenticationType,
        Integer statusCode,
        Instant from,
        Instant to,
        int page,
        int size) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static RuntimeAnalyticsEventFilter of(
            String apiContext,
            String apiVersion,
            AuthenticationType authenticationType,
            Integer statusCode,
            Instant from,
            Instant to,
            int page,
            int size) {
        if (page < DEFAULT_PAGE) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException("size must not exceed " + MAX_SIZE);
        }
        if (statusCode != null && (statusCode < 100 || statusCode > 599)) {
            throw new IllegalArgumentException("statusCode must be between 100 and 599");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        return new RuntimeAnalyticsEventFilter(
                trimToNull(apiContext), trimToNull(apiVersion),
                authenticationType, statusCode, from, to, page, size);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}