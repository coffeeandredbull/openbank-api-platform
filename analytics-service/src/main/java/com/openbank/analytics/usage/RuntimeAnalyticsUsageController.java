package com.openbank.analytics.usage;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Public usage-summary endpoint {@code GET /analytics/usage} (Phase 23, Slice 6),
 * protected by an {@code ADMIN}/{@code DEVELOPER} JWT. All parameters are
 * optional; validation happens in {@link RuntimeAnalyticsUsageFilter}, and the
 * aggregation executes in PostgreSQL.
 */
@RestController
@RequestMapping("/analytics/usage")
public class RuntimeAnalyticsUsageController {

    private final RuntimeAnalyticsUsageService usageService;

    public RuntimeAnalyticsUsageController(RuntimeAnalyticsUsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping
    public RuntimeAnalyticsUsageSummary usage(
            @RequestParam(required = false) String apiContext,
            @RequestParam(required = false) String apiVersion,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return usageService.summarize(RuntimeAnalyticsUsageFilter.of(
                apiContext, apiVersion, from, to));
    }
}