package com.openbank.analytics.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeAnalyticsEventFilterTest {

    private static final Instant FROM = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-20T12:00:00Z");

    @Test
    void defaultsAreZeroAndTwenty() {
        RuntimeAnalyticsEventFilter filter = RuntimeAnalyticsEventFilter.of(
                null, null, null, null, null, null, 0, 20);

        assertThat(filter.page()).isZero();
        assertThat(filter.size()).isEqualTo(20);
    }

    @Test
    void blankFilterValuesBecomeNull() {
        RuntimeAnalyticsEventFilter filter = RuntimeAnalyticsEventFilter.of(
                " ", "\t", null, null, null, null, 0, 20);

        assertThat(filter.apiContext()).isNull();
        assertThat(filter.apiVersion()).isNull();
    }

    @Test
    void preservesProvidedFiltersTrimmed() {
        RuntimeAnalyticsEventFilter filter = RuntimeAnalyticsEventFilter.of(
                " /runtime/payments ", " v2 ", AuthenticationType.CLIENT_CREDENTIAL, 201,
                FROM, TO, 1, 50);

        assertThat(filter.page()).isEqualTo(1);
        assertThat(filter.size()).isEqualTo(50);
        assertThat(filter.apiContext()).isEqualTo("/runtime/payments");
        assertThat(filter.apiVersion()).isEqualTo("v2");
        assertThat(filter.authenticationType()).isEqualTo(AuthenticationType.CLIENT_CREDENTIAL);
        assertThat(filter.statusCode()).isEqualTo(201);
        assertThat(filter.from()).isEqualTo(FROM);
        assertThat(filter.to()).isEqualTo(TO);
    }

    @Test
    void rejectsNegativePage() {
        assertThatThrownBy(() -> RuntimeAnalyticsEventFilter.of(
                null, null, null, null, null, null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
    }

    @Test
    void rejectsSizeOutsideRange() {
        assertThatThrownBy(() -> RuntimeAnalyticsEventFilter.of(
                null, null, null, null, null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> RuntimeAnalyticsEventFilter.of(
                null, null, null, null, null, null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
    }

    @Test
    void rejectsOutOfRangeStatusCode() {
        assertThatThrownBy(() -> RuntimeAnalyticsEventFilter.of(
                null, null, null, 99, null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statusCode");
        assertThatThrownBy(() -> RuntimeAnalyticsEventFilter.of(
                null, null, null, 600, null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statusCode");
    }

    @Test
    void rejectsFromAfterTo() {
        assertThatThrownBy(() -> RuntimeAnalyticsEventFilter.of(
                null, null, null, null, FROM, FROM.minusSeconds(1), 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }
}