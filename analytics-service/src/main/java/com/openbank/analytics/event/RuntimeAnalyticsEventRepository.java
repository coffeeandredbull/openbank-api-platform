package com.openbank.analytics.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RuntimeAnalyticsEventRepository
        extends JpaRepository<RuntimeAnalyticsEventEntity, Long>,
        JpaSpecificationExecutor<RuntimeAnalyticsEventEntity> {
}