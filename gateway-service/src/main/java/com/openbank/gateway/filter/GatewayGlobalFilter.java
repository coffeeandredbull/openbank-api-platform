package com.openbank.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class GatewayGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayGlobalFilter.class);

    private final ObjectMapper objectMapper;

    public GatewayGlobalFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        return chain.filter(exchange)
                .onErrorResume(throwable -> handleUpstreamFailure(exchange, throwable))
                .doFinally(signalType -> logRequest(exchange, startTime));
    }

    private Mono<Void> handleUpstreamFailure(ServerWebExchange exchange, Throwable throwable) {
        if (!isUpstreamFailure(throwable)) {
            return Mono.error(throwable);
        }
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(throwable);
        }
        log.warn("gateway upstream failure method={} path={} routeId={} cause={}",
                requestMethod(exchange),
                exchange.getRequest().getPath().value(),
                routeId(exchange),
                throwable.toString());

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("timestamp", Instant.now().toString());
        errorBody.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        errorBody.put("error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        errorBody.put("code", "UPSTREAM_SERVICE_UNAVAILABLE");
        errorBody.put("message", "The requested service is currently unavailable");

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorBody);
        } catch (JsonProcessingException e) {
            log.error("gateway failed to serialize upstream error response", e);
            return Mono.error(throwable);
        }
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private boolean isUpstreamFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof IOException || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private void logRequest(ServerWebExchange exchange, long startTime) {
        long durationMs = System.currentTimeMillis() - startTime;
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        log.info("gateway request method={} path={} routeId={} status={} durationMs={}",
                requestMethod(exchange),
                exchange.getRequest().getPath().value(),
                routeId(exchange),
                statusCode != null ? statusCode.value() : "none",
                durationMs);
    }

    private String requestMethod(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() != null
                ? exchange.getRequest().getMethod().name()
                : "unknown";
    }

    private String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null ? route.getId() : "none";
    }
}