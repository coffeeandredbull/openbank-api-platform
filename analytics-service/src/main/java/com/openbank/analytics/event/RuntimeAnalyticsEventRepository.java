package com.openbank.analytics.event;

import com.openbank.analytics.usage.RuntimeAnalyticsUsageProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RuntimeAnalyticsEventRepository
        extends JpaRepository<RuntimeAnalyticsEventEntity, Long>,
        JpaSpecificationExecutor<RuntimeAnalyticsEventEntity> {

    /**
     * Single-row usage summary computed entirely in PostgreSQL (Phase 23,
     * Slice 6): one native aggregate SELECT with no {@code GROUP BY}, so it
     * always returns exactly one row — an empty or fully filtered-out window
     * yields zero counts and a {@code 0.0} average latency (via
     * {@code COALESCE}). Native SQL is used because the optional
     * {@code from}/{@code to} bounds must be explicitly cast to
     * {@code timestamptz}: PostgreSQL cannot infer the type of a parameter
     * used only in an {@code IS NULL} test, and a bare {@code >=}/{@code <=}
     * comparison against the {@code timestamptz} column is ambiguous
     * (timestamp vs. timestamptz operator candidates) — both raise SQLState
     * 42P18 ("could not determine data type of parameter"). Casting every
     * occurrence to {@code timestamptz} supplies the type (PostgreSQL 16
     * supports casts on bound parameters, removing the cast requirement, but
     * the explicit casts keep the statement inferable on any supported
     * version). {@code apiContext}/{@code apiVersion} are exact matches and
     * {@code from}/{@code to} are inclusive bounds; absent filters are passed
     * as {@code null}.
     */
    @Query(value = """
            SELECT COUNT(e.id) AS "totalRequests",
                   COUNT(CASE WHEN e.status_code BETWEEN 200 AND 299 THEN 1 END) AS "successfulRequests",
                   COUNT(CASE WHEN e.status_code BETWEEN 400 AND 499 THEN 1 END) AS "clientErrorRequests",
                   COUNT(CASE WHEN e.status_code BETWEEN 500 AND 599 THEN 1 END) AS "serverErrorRequests",
                   COALESCE(AVG(e.latency_ms), 0) AS "averageLatencyMs"
              FROM runtime_analytics_events e
             WHERE (:apiContext IS NULL OR e.api_context = :apiContext)
               AND (:apiVersion IS NULL OR e.api_version = :apiVersion)
               AND (CAST(:from AS timestamptz) IS NULL OR e.event_timestamp >= CAST(:from AS timestamptz))
               AND (CAST(:to AS timestamptz) IS NULL OR e.event_timestamp <= CAST(:to AS timestamptz))
            """, nativeQuery = true)
    RuntimeAnalyticsUsageProjection aggregateUsage(
            @Param("apiContext") String apiContext,
            @Param("apiVersion") String apiVersion,
            @Param("from") Instant from,
            @Param("to") Instant to);
}