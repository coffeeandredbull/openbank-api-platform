package com.openbank.analytics.internal;

import com.openbank.analytics.event.RuntimeAnalyticsEventService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal ingestion endpoint for runtime analytics events (Phase 23, Slice 4).
 * It accepts one captured event per request, validates it, and persists it via
 * the existing {@link RuntimeAnalyticsEventService}. This is not a public API:
 * it is reachable only behind the internal token filter and exposes no
 * reporting/querying capability.
 */
@RestController
@RequestMapping("/internal/analytics/events")
public class RuntimeAnalyticsEventIngestionController {

    private final RuntimeAnalyticsEventService eventService;

    public RuntimeAnalyticsEventIngestionController(RuntimeAnalyticsEventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<Void> ingest(@Valid @RequestBody RuntimeAnalyticsEventRequest request) {
        eventService.save(request.toEvent());
        return ResponseEntity.accepted().build();
    }
}