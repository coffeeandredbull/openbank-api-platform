package com.openbank.analytics.event;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes {@code GET /analytics/events} queries (Phase 23, Slice 5). Filtering
 * (exact matches and inclusive time bounds), the deterministic sort
 * {@code event_timestamp DESC, id DESC}, and pagination are all pushed to
 * PostgreSQL via Spring Data JPA (a JpaSpecificationExecutor + Pageable); no
 * rows are loaded into Java memory, filtered with streams, or paginated in
 * memory.
 */
@Service
public class RuntimeAnalyticsEventQueryService {

    private final RuntimeAnalyticsEventRepository repository;

    public RuntimeAnalyticsEventQueryService(RuntimeAnalyticsEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public RuntimeAnalyticsEventPageResponse query(RuntimeAnalyticsEventFilter filter) {
        Pageable pageable = PageRequest.of(
                filter.page(),
                filter.size(),
                Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id")));
        Page<RuntimeAnalyticsEventEntity> result = repository.findAll(toSpecification(filter), pageable);
        return RuntimeAnalyticsEventPageResponse.from(result);
    }

    private static Specification<RuntimeAnalyticsEventEntity> toSpecification(RuntimeAnalyticsEventFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.apiContext() != null) {
                predicates.add(cb.equal(root.get("apiContext"), filter.apiContext()));
            }
            if (filter.apiVersion() != null) {
                predicates.add(cb.equal(root.get("apiVersion"), filter.apiVersion()));
            }
            if (filter.authenticationType() != null) {
                predicates.add(cb.equal(root.get("authenticationType"), filter.authenticationType()));
            }
            if (filter.statusCode() != null) {
                predicates.add(cb.equal(root.get("statusCode"), filter.statusCode()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), filter.to()));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}