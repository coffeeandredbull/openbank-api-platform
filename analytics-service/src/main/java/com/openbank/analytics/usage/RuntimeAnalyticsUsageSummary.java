package com.openbank.analytics.usage;

/**
 * Aggregated usage summary for {@code GET /analytics/usage} (Phase 23, Slice 6).
 * Exposes only derived counters and an average latency — never credentials,
 * tokens, headers, bodies, or any raw event row. With an empty or fully
 * filtered-out window the counters are {@code 0} and the average latency is
 * {@code 0.0}.
 */
public record RuntimeAnalyticsUsageSummary(
        long totalRequests,
        long successfulRequests,
        long clientErrorRequests,
        long serverErrorRequests,
        double averageLatencyMs) {

    public static RuntimeAnalyticsUsageSummary from(RuntimeAnalyticsUsageProjection projection) {
        return new RuntimeAnalyticsUsageSummary(
                projection.getTotalRequests(),
                projection.getSuccessfulRequests(),
                projection.getClientErrorRequests(),
                projection.getServerErrorRequests(),
                projection.getAverageLatencyMs());
    }
}