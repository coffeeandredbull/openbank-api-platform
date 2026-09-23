package com.openbank.analytics.usage;

import com.openbank.analytics.event.RuntimeAnalyticsEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the usage summary for {@code GET /analytics/usage} (Phase 23,
 * Slice 6). The aggregation itself runs entirely in PostgreSQL via the
 * repository's single aggregate query; this service only wires the validated
 * filter into that query and maps the projection to the response DTO. No
 * analytics rows are loaded into Java memory.
 */
@Service
public class RuntimeAnalyticsUsageService {

    private final RuntimeAnalyticsEventRepository repository;

    public RuntimeAnalyticsUsageService(RuntimeAnalyticsEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public RuntimeAnalyticsUsageSummary summarize(RuntimeAnalyticsUsageFilter filter) {
        RuntimeAnalyticsUsageProjection projection = repository.aggregateUsage(
                filter.apiContext(), filter.apiVersion(), filter.from(), filter.to());
        return RuntimeAnalyticsUsageSummary.from(projection);
    }
}