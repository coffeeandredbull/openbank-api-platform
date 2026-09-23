package com.openbank.gateway.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRuntimeAnalyticsEventSinkTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private final InMemoryRuntimeAnalyticsEventSink sink = new InMemoryRuntimeAnalyticsEventSink(
            new AnalyticsDeliveryProperties("http://localhost:8083", "", 500L, 2));

    private static RuntimeAnalyticsEvent event(long latencyMs) {
        return new RuntimeAnalyticsEvent(NOW, "/payments", "v1", HttpMethod.GET, 200,
                latencyMs, AuthenticationType.JWT, 1L, null);
    }

    @Test
    void recordsEventsInRecordingOrder() {
        sink.record(event(1));
        sink.record(event(2));

        List<RuntimeAnalyticsEvent> snapshot = sink.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).latencyMs()).isEqualTo(1L);
        assertThat(snapshot.get(1).latencyMs()).isEqualTo(2L);
    }

    @Test
    void dropsNewEventsWhenBufferIsFull() {
        sink.record(event(1));
        sink.record(event(2));
        sink.record(event(3));

        List<RuntimeAnalyticsEvent> snapshot = sink.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).latencyMs()).isEqualTo(1L);
        assertThat(snapshot.get(1).latencyMs()).isEqualTo(2L);
    }

    @Test
    void takeReturnsEventsInFifoOrder() throws InterruptedException {
        sink.record(event(1));
        sink.record(event(2));

        assertThat(sink.take().latencyMs()).isEqualTo(1L);
        assertThat(sink.take().latencyMs()).isEqualTo(2L);
        assertThat(sink.snapshot()).isEmpty();
    }

    @Test
    void clearEmptiesTheSink() {
        sink.record(event(1));
        sink.clear();

        assertThat(sink.snapshot()).isEmpty();
    }
}