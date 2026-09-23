package com.openbank.gateway.analytics;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Application-local, thread-safe, bounded in-memory queue of
 * {@link RuntimeAnalyticsEvent}s (Phase 23). The runtime analytics capture
 * filter produces into it on the request thread — never blocking, silently
 * dropping when the bounded buffer is full — and the
 * {@link RuntimeAnalyticsDeliveryWorker} consumes from it asynchronously for
 * best-effort delivery to the analytics service (Slice 4). It is not a
 * persistence or guaranteed-delivery subsystem: analytics can never slow down
 * or break a request.
 */
@Component
public class InMemoryRuntimeAnalyticsEventSink {

    private final ArrayBlockingQueue<RuntimeAnalyticsEvent> events;

    public InMemoryRuntimeAnalyticsEventSink(AnalyticsDeliveryProperties properties) {
        this.events = new ArrayBlockingQueue<>(properties.queueCapacity());
    }

    /**
     * Records an event. Non-blocking and failure-free: drops the event when the
     * buffer is full.
     */
    public void record(RuntimeAnalyticsEvent event) {
        events.offer(event);
    }

    /**
     * Returns and removes the next event, blocking until one is available.
     * Intended for the delivery worker loop.
     */
    public RuntimeAnalyticsEvent take() throws InterruptedException {
        return events.take();
    }

    /**
     * Snapshot of the queued events in recording order, as an unmodifiable
     * list. Intended for test observation of produced events.
     */
    public List<RuntimeAnalyticsEvent> snapshot() {
        return List.copyOf(events);
    }

    /**
     * Empties the sink. Intended for test reset between cases.
     */
    public void clear() {
        events.clear();
    }
}