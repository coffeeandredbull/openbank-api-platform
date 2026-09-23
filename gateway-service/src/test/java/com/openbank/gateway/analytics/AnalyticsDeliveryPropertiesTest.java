package com.openbank.gateway.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsDeliveryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AnalyticsDeliveryProperties.class);

    @Test
    void defaultsPointAtLocalAnalyticsServiceWithDeliveryDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AnalyticsDeliveryProperties properties = context.getBean(AnalyticsDeliveryProperties.class);
            assertThat(properties.analyticsServiceUrl()).isEqualTo("http://localhost:8083");
            assertThat(properties.internalToken()).isEmpty();
            assertThat(properties.deliveryEnabled()).isFalse();
            assertThat(properties.deliveryTimeoutMs()).isEqualTo(500L);
            assertThat(properties.queueCapacity()).isEqualTo(1024);
        });
    }

    @Test
    void environmentVariablesOverrideTheDefaults() {
        runner.withPropertyValues(
                        "ANALYTICS_SERVICE_URL=http://analytics.internal:9090",
                        "ANALYTICS_INTERNAL_TOKEN=shared-secret",
                        "ANALYTICS_DELIVERY_TIMEOUT_MS=250",
                        "ANALYTICS_QUEUE_CAPACITY=16")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AnalyticsDeliveryProperties properties = context.getBean(AnalyticsDeliveryProperties.class);
                    assertThat(properties.analyticsServiceUrl()).isEqualTo("http://analytics.internal:9090");
                    assertThat(properties.internalToken()).isEqualTo("shared-secret");
                    assertThat(properties.deliveryEnabled()).isTrue();
                    assertThat(properties.deliveryTimeoutMs()).isEqualTo(250L);
                    assertThat(properties.queueCapacity()).isEqualTo(16);
                });
    }

    @Test
    void blankUrlIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_SERVICE_URL=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void malformedUrlIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_SERVICE_URL=http://exa{mple}.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingHostIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_SERVICE_URL=http:///events")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unsupportedSchemeIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_SERVICE_URL=ftp://example.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void whitespaceIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_SERVICE_URL=http://example .com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void userinfoIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_SERVICE_URL=http://user:secret@example.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void zeroTimeoutIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_DELIVERY_TIMEOUT_MS=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void negativeTimeoutIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_DELIVERY_TIMEOUT_MS=-10")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void excessiveTimeoutIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_DELIVERY_TIMEOUT_MS=60001")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void maximumAllowedTimeoutIsAccepted() {
        runner.withPropertyValues("ANALYTICS_DELIVERY_TIMEOUT_MS=60000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AnalyticsDeliveryProperties.class).deliveryTimeoutMs())
                            .isEqualTo(60_000L);
                });
    }

    @Test
    void malformedTimeoutIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_DELIVERY_TIMEOUT_MS=soon")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void zeroQueueCapacityIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_QUEUE_CAPACITY=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void negativeQueueCapacityIsRejectedAtStartup() {
        runner.withPropertyValues("ANALYTICS_QUEUE_CAPACITY=-1")
                .run(context -> assertThat(context).hasFailed());
    }
}