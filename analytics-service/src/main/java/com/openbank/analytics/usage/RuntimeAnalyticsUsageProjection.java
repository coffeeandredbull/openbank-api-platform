package com.openbank.analytics.usage;

/**
 * Result of the single-row usage aggregation computed by PostgreSQL (Phase 23,
 * Slice 6). The getters map to the {@code AS} aliases of the aggregate query;
 * because the query has no {@code GROUP BY} it always produces exactly one row,
 * so the average latency is never {@code null} (a zero-row window yields
 * {@code 0} counts and an {@code averageLatencyMs} of {@code 0.0}).
 */
public interface RuntimeAnalyticsUsageProjection {

    long getTotalRequests();

    long getSuccessfulRequests();

    long getClientErrorRequests();

    long getServerErrorRequests();

    double getAverageLatencyMs();
}