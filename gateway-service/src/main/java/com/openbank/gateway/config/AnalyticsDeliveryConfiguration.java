package com.openbank.gateway.config;

import com.openbank.gateway.analytics.AnalyticsDeliveryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Provides the {@link WebClient} used to deliver captured runtime analytics
 * events to the analytics service (Phase 23, Slice 4). The base URL is the
 * analytics service location; the shared internal token is added per request
 * by the delivery worker and never lives in the client.
 */
@Configuration(proxyBeanMethods = false)
public class AnalyticsDeliveryConfiguration {

    @Bean
    WebClient analyticsServiceWebClient(AnalyticsDeliveryProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.analyticsServiceUrl())
                .build();
    }
}