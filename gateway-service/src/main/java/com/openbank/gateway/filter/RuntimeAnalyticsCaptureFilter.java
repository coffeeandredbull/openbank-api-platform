package com.openbank.gateway.filter;

import com.openbank.gateway.analytics.AuthenticationType;
import com.openbank.gateway.analytics.InMemoryRuntimeAnalyticsEventSink;
import com.openbank.gateway.analytics.RuntimeAnalyticsEvent;
import com.openbank.gateway.auth.ClientCredentialIdentity;
import com.openbank.gateway.auth.JwtIdentity;
import com.openbank.gateway.ratelimit.RuntimeApiPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Captures one {@link RuntimeAnalyticsEvent} per authenticated runtime API
 * request (Phase 23, Slice 2). It runs as the outermost global filter (order
 * -300, earlier than the authentication/subscription/rate-limiting chain and
 * the managed-API routing) so it surrounds the whole runtime request lifecycle:
 * the start time is recorded before any downstream processing and the event is
 * built after the final response status is known.
 *
 * <p>Analytics is strictly observational. A failure to build or record an event
 * is logged and swallowed — it never replaces the real upstream response, never
 * turns a successful request into a 5xx, and never interrupts authentication,
 * subscription enforcement, rate limiting, or routing.
 *
 * <p>Only requests that reached the runtime chain with a verified identity
 * produce an event: the identity attributes established by
 * {@link JwtAuthenticationFilter}/{@link ClientCredentialAuthenticationFilter}
 * are reused so nothing is re-parsed, no credentials are touched, and no
 * additional upstream call is made. Management routes and authentication
 * failures (no verified identity) never produce an event. An authenticated
 * request that is later rejected by subscription enforcement or rate limiting
 * still yields an event carrying its final response status, because the runtime
 * chain was reached with a verified identity.
 */
@Component
public class RuntimeAnalyticsCaptureFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RuntimeAnalyticsCaptureFilter.class);

    private static final int ORDER = -300;

    private final InMemoryRuntimeAnalyticsEventSink sink;

    public RuntimeAnalyticsCaptureFilter(InMemoryRuntimeAnalyticsEventSink sink) {
        this.sink = sink;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!RuntimeApiPath.isRuntimePath(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        long startNanos = System.nanoTime();
        Instant capturedAt = Instant.now();
        return chain.filter(exchange)
                .doFinally(signalType -> capture(exchange, startNanos, capturedAt));
    }

    private void capture(ServerWebExchange exchange, long startNanos, Instant capturedAt) {
        try {
            RuntimeApiPath target = RuntimeApiPath.from(exchange.getRequest().getPath().value()).orElse(null);
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            HttpMethod method = exchange.getRequest().getMethod();
            Identity identity = identity(exchange);
            if (target == null || status == null || method == null || identity == null) {
                return;
            }
            long latencyMillis = Math.max(0L,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
            sink.record(new RuntimeAnalyticsEvent(
                    capturedAt,
                    target.contextPath(),
                    target.version(),
                    method,
                    status.value(),
                    latencyMillis,
                    identity.authenticationType(),
                    identity.userId(),
                    identity.applicationId()));
        } catch (RuntimeException e) {
            log.warn("gateway analytics capture discarded path={} cause={}",
                    exchange.getRequest().getPath().value(), e.toString());
        }
    }

    private Identity identity(ServerWebExchange exchange) {
        JwtIdentity jwt = exchange.getAttribute(JwtAuthenticationFilter.IDENTITY_ATTRIBUTE);
        if (jwt != null) {
            return new Identity(AuthenticationType.JWT, jwt.userId(), null);
        }
        ClientCredentialIdentity clientCredential =
                exchange.getAttribute(ClientCredentialAuthenticationFilter.CLIENT_CREDENTIAL_ATTRIBUTE);
        if (clientCredential != null) {
            return new Identity(
                    AuthenticationType.CLIENT_CREDENTIAL,
                    clientCredential.ownerUserId(),
                    clientCredential.applicationId());
        }
        return null;
    }

    private record Identity(AuthenticationType authenticationType, Long userId, Long applicationId) {
    }
}