package com.openbank.analytics.usage;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeAnalyticsUsageFilterTest {

    private static final Instant FROM = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-20T12:00:00Z");

    @Test
    void absentFiltersStayNull() {
        RuntimeAnalyticsUsageFilter filter = RuntimeAnalyticsUsageFilter.of(null, null, null, null);

        assertThat(filter.apiContext()).isNull();
        assertThat(filter.apiVersion()).isNull();
        assertThat(filter.from()).isNull();
        assertThat(filter.to()).isNull();
    }

    @Test
    void blankFilterValuesBecomeNull() {
        RuntimeAnalyticsUsageFilter filter = RuntimeAnalyticsUsageFilter.of(" ", "\t", null, null);

        assertThat(filter.apiContext()).isNull();
        assertThat(filter.apiVersion()).isNull();
    }

    @Test
    void preservesProvidedFiltersTrimmed() {
        RuntimeAnalyticsUsageFilter filter = RuntimeAnalyticsUsageFilter.of(
                " /runtime/payments ", " v2 ", FROM, TO);

        assertThat(filter.apiContext()).isEqualTo("/runtime/payments");
        assertThat(filter.apiVersion()).isEqualTo("v2");
        assertThat(filter.from()).isEqualTo(FROM);
        assertThat(filter.to()).isEqualTo(TO);
    }

    @Test
    void rejectsFromAfterTo() {
        assertThatThrownBy(() -> RuntimeAnalyticsUsageFilter.of(null, null, FROM, FROM.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }

    @Test
    void equalFromAndToAreAllowed() {
        RuntimeAnalyticsUsageFilter filter = RuntimeAnalyticsUsageFilter.of(null, null, FROM, FROM);

        assertThat(filter.from()).isEqualTo(FROM);
        assertThat(filter.to()).isEqualTo(FROM);
    }
}