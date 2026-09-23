package com.openbank.analytics.event;

import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Optional;

@Service
@Validated
public class RuntimeAnalyticsEventService {

    private final RuntimeAnalyticsEventRepository repository;

    public RuntimeAnalyticsEventService(RuntimeAnalyticsEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public long save(@Valid RuntimeAnalyticsEvent event) {
        return repository.save(RuntimeAnalyticsEventEntity.from(event)).getId();
    }

    @Transactional(readOnly = true)
    public Optional<RuntimeAnalyticsEvent> findById(long id) {
        return repository.findById(id).map(RuntimeAnalyticsEventEntity::toEvent);
    }
}