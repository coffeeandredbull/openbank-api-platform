package com.openbank.analytics.event;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeAnalyticsEventRepository extends JpaRepository<RuntimeAnalyticsEventEntity, Long> {
}