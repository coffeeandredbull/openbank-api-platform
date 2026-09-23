package com.openbank.gateway;

import com.openbank.gateway.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RateLimitPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimitProperties.class);

    @Test
    void defaultsTo100RequestsPer60SecondWindow() {
        runner.run(context -> {
            org.assertj.core.api.Assertions.assertThat(context).hasNotFailed();
            RateLimitProperties properties = context.getBean(RateLimitProperties.class);
            org.assertj.core.api.Assertions.assertThat(properties.requests()).isEqualTo(100);
            org.assertj.core.api.Assertions.assertThat(properties.windowSeconds()).isEqualTo(60);
        });
    }

    @Test
    void environmentVariablesOverrideTheDefaults() {
        runner.withPropertyValues("RATE_LIMIT_REQUESTS=25", "RATE_LIMIT_WINDOW_SECONDS=10")
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context).hasNotFailed();
                    RateLimitProperties properties = context.getBean(RateLimitProperties.class);
                    org.assertj.core.api.Assertions.assertThat(properties.requests()).isEqualTo(25);
                    org.assertj.core.api.Assertions.assertThat(properties.windowSeconds()).isEqualTo(10);
                });
    }

    @Test
    void zeroRequestsIsRejectedAtStartup() {
        runner.withPropertyValues("RATE_LIMIT_REQUESTS=0")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasFailed());
    }

    @Test
    void negativeRequestsIsRejectedAtStartup() {
        runner.withPropertyValues("RATE_LIMIT_REQUESTS=-5")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasFailed());
    }

    @Test
    void malformedRequestsIsRejectedAtStartup() {
        runner.withPropertyValues("RATE_LIMIT_REQUESTS=abc")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasFailed());
    }

    @Test
    void zeroWindowIsRejectedAtStartup() {
        runner.withPropertyValues("RATE_LIMIT_WINDOW_SECONDS=0")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasFailed());
    }

    @Test
    void negativeWindowIsRejectedAtStartup() {
        runner.withPropertyValues("RATE_LIMIT_WINDOW_SECONDS=-1")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasFailed());
    }

    @Test
    void malformedWindowIsRejectedAtStartup() {
        runner.withPropertyValues("RATE_LIMIT_WINDOW_SECONDS=soon")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasFailed());
    }
}