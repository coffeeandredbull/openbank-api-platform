package com.openbank.gateway.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous, best-effort delivery worker (Phase 23, Slice 4). A single
 * daemon thread drains {@link InMemoryRuntimeAnalyticsEventSink} and POSTs each
 * event to the analytics service's internal ingestion endpoint over HTTP via
 * the configured {@code WebClient}.
 *
 * <p>Delivery is strictly fire-and-forget: every attempt has a bounded timeout,
 * a 2xx response counts as delivered, and any other outcome (non-2xx status,
 * timeout, transport error) results in the event being dropped — there is no
 * retry, no batching, and no exactly-once guarantee. The worker is isolated
 * from the request path, so a slow or unavailable analytics service can never
 * delay or fail a runtime request. When no internal token is configured
 * ({@link AnalyticsDeliveryProperties#deliveryEnabled()}), delivery is disabled
 * entirely and the worker does not start.
 */
@Component
public class RuntimeAnalyticsDeliveryWorker implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RuntimeAnalyticsDeliveryWorker.class);

    private static final String DELIVERY_PATH = "/internal/analytics/events";

    private final AnalyticsDeliveryProperties properties;
    private final InMemoryRuntimeAnalyticsEventSink sink;
    private final WebClient analyticsWebClient;

    private ExecutorService executor;

    public RuntimeAnalyticsDeliveryWorker(
            AnalyticsDeliveryProperties properties,
            InMemoryRuntimeAnalyticsEventSink sink,
            WebClient analyticsWebClient) {
        this.properties = properties;
        this.sink = sink;
        this.analyticsWebClient = analyticsWebClient;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.deliveryEnabled()) {
            log.info("gateway analytics delivery disabled: ANALYTICS_INTERNAL_TOKEN is not configured");
            return;
        }
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-analytics-delivery");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::deliveryLoop);
    }

    @Override
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void deliveryLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            RuntimeAnalyticsEvent event;
            try {
                event = sink.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            deliver(event);
        }
    }

    void deliver(RuntimeAnalyticsEvent event) {
        try {
            analyticsWebClient.post()
                    .uri(DELIVERY_PATH)
                    .header(AnalyticsDeliveryProperties.INTERNAL_TOKEN_HEADER, properties.internalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(AnalyticsEventDeliveryPayload.from(event))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(properties.deliveryTimeoutMs()));
            log.debug("gateway analytics event delivered");
        } catch (WebClientResponseException e) {
            log.warn("gateway analytics delivery rejected destination={} status={}",
                    properties.analyticsServiceUrl(), e.getStatusCode().value());
        } catch (WebClientRequestException e) {
            log.warn("gateway analytics delivery failed destination={} cause={}",
                    properties.analyticsServiceUrl(), e.toString());
        } catch (RuntimeException e) {
            log.warn("gateway analytics delivery failed destination={} cause={}",
                    properties.analyticsServiceUrl(), e.toString());
        }
    }
}