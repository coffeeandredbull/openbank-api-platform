package com.openbank.gateway;

import com.openbank.gateway.ratelimit.RateLimitKeyGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeyGeneratorTest {

    private final RateLimitKeyGenerator generator = new RateLimitKeyGenerator();

    @Test
    void keyContainsTheJwtSubjectContextPathAndVersion() {
        assertThat(generator.keyFor(42, "/payments", "v1"))
                .isEqualTo("rate_limit:42:/payments:v1");
    }

    @Test
    void differentUsersProduceDifferentKeys() {
        assertThat(generator.keyFor(1, "/payments", "v1"))
                .isNotEqualTo(generator.keyFor(2, "/payments", "v1"));
    }

    @Test
    void differentVersionsProduceDifferentKeys() {
        assertThat(generator.keyFor(1, "/payments", "v1"))
                .isNotEqualTo(generator.keyFor(1, "/payments", "v2"));
    }

    @Test
    void differentContextPathsProduceDifferentKeys() {
        assertThat(generator.keyFor(1, "/payments", "v1"))
                .isNotEqualTo(generator.keyFor(1, "/accounts", "v1"));
    }

    @Test
    void keyIsDerivedOnlyFromJwtSubjectAndApiTarget() {
        assertThat(generator.keyFor(7, "/accounts", "v12"))
                .isEqualTo("rate_limit:7:/accounts:v12");
    }

    @Test
    void applicationScopedKeyContainsApplicationIdContextPathAndVersion() {
        assertThat(generator.keyForApplication(7, "/payments", "v1"))
                .isEqualTo("rate_limit:app:7:/payments:v1");
    }

    @Test
    void applicationScopedKeyIsDistinctFromTheUserScopedKey() {
        assertThat(generator.keyForApplication(7, "/payments", "v1"))
                .isNotEqualTo(generator.keyFor(7, "/payments", "v1"));
    }

    @Test
    void differentApplicationsProduceDifferentApplicationScopedKeys() {
        assertThat(generator.keyForApplication(1, "/payments", "v1"))
                .isNotEqualTo(generator.keyForApplication(2, "/payments", "v1"));
    }
}