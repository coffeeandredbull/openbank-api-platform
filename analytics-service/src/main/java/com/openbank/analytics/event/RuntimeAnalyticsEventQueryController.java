package com.openbank.analytics.event;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Public query endpoint {@code GET /analytics/events} (Phase 23, Slice 5),
 * protected by a {@code ADMIN}/{@code DEVELOPER} JWT. All parameters are
 * optional; validation happens in {@link RuntimeAnalyticsEventFilter}, and the
 * query executes in PostgreSQL (spec + sort + pagination).
 */
@RestController
@RequestMapping("/analytics/events")
public class RuntimeAnalyticsEventQueryController {

    private final RuntimeAnalyticsEventQueryService queryService;

    public RuntimeAnalyticsEventQueryController(RuntimeAnalyticsEventQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public RuntimeAnalyticsEventPageResponse list(
            @RequestParam(required = false) String apiContext,
            @RequestParam(required = false) String apiVersion,
            @RequestParam(required = false) AuthenticationType authenticationType,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.query(RuntimeAnalyticsEventFilter.of(
                apiContext, apiVersion, authenticationType, statusCode, from, to, page, size));
    }
}