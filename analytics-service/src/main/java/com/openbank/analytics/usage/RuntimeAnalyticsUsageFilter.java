package com.openbank.analytics.usage;

import java.time.Instant;

/**
 * Validated query parameters for {@code GET /analytics/usage} (Phase 23,
 * Slice 6). All filters are optional: {@code apiContext} and {@code apiVersion}
 * are exact matches and {@code from}/{@code to} are inclusive {@link Instant}
 * bounds. Validation fails fast through {@link IllegalArgumentException},
 * which the centralized handler maps to HTTP 400 without echoing values back.
 */
public record RuntimeAnalyticsUsageFilter(
        String apiContext,
        String apiVersion,
        Instant from,
        Instant to) {

    public static RuntimeAnalyticsUsageFilter of(
            String apiContext,
            String apiVersion,
            Instant from,
            Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        return new RuntimeAnalyticsUsageFilter(
                trimToNull(apiContext), trimToNull(apiVersion), from, to);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}