package com.openbank.analytics.event;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paged response for {@code GET /analytics/events} (Phase 23, Slice 5).
 * Items are {@link RuntimeAnalyticsEventResponse} DTOs; the pagination
 * metadata (page, size, totalElements, totalPages, last) mirrors the page
 * returned by PostgreSQL so behavior stays explicit.
 */
public record RuntimeAnalyticsEventPageResponse(
        List<RuntimeAnalyticsEventResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static RuntimeAnalyticsEventPageResponse from(Page<RuntimeAnalyticsEventEntity> page) {
        List<RuntimeAnalyticsEventResponse> items = page.getContent().stream()
                .map(RuntimeAnalyticsEventResponse::from)
                .toList();
        return new RuntimeAnalyticsEventPageResponse(
                items, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }
}