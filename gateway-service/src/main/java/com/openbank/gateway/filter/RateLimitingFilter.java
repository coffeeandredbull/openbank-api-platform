package com.openbank.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.gateway.auth.JwtIdentity;
import com.openbank.gateway.ratelimit.RateLimitService;
import com.openbank.gateway.ratelimit.RuntimeApiPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RateLimitingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private static final String RUNTIME_API_PATH = "/runtime/apis";

    private static final String CODE_EXCEEDED = "RATE_LIMIT_EXCEEDED";
    private static final String MESSAGE_EXCEEDED = "Rate limit exceeded";
    private static final String CODE_UNAVAILABLE = "RATE_LIMIT_SERVICE_UNAVAILABLE";
    private static final String MESSAGE_UNAVAILABLE = "Rate limiting service unavailable";

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    public RateLimitingFilter(RateLimitService rateLimitService, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return -120;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!isRuntimeApiPath(path)) {
            return chain.filter(exchange);
        }
        long startTime = System.currentTimeMillis();
        JwtIdentity identity = exchange.getAttribute(JwtAuthenticationFilter.IDENTITY_ATTRIBUTE);
        if (identity == null) {
            return writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, CODE_UNAVAILABLE,
                    MESSAGE_UNAVAILABLE, path, startTime);
        }
        RuntimeApiPath target = RuntimeApiPath.from(path).orElse(null);
        if (target == null) {
            return writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, CODE_UNAVAILABLE,
                    MESSAGE_UNAVAILABLE, path, startTime);
        }
        return Mono.fromCallable(() ->
                        rateLimitService.evaluate(identity.userId(), target.contextPath(), target.version()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(decision -> switch (decision.state()) {
                    case ALLOWED -> {
                        log.info("gateway rate limit allowed method={} path={} routeId={} durationMs={}",
                                requestMethod(exchange),
                                path,
                                routeId(exchange),
                                System.currentTimeMillis() - startTime);
                        yield chain.filter(exchange);
                    }
                    case DENIED -> writeError(exchange, HttpStatus.TOO_MANY_REQUESTS, CODE_EXCEEDED,
                            MESSAGE_EXCEEDED, path, startTime, decision.retryAfterSeconds());
                    case UNAVAILABLE -> writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, CODE_UNAVAILABLE,
                            MESSAGE_UNAVAILABLE, path, startTime);
                });
    }

    private boolean isRuntimeApiPath(String path) {
        return path.equals(RUNTIME_API_PATH) || path.startsWith(RUNTIME_API_PATH + "/");
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code,
            String message, String path, long startTime) {
        return writeError(exchange, status, code, message, path, startTime, 0);
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code,
            String message, String path, long startTime, long retryAfterSeconds) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (retryAfterSeconds > 0) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }

        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("timestamp", Instant.now().toString());
        errorBody.put("status", status.value());
        errorBody.put("error", status.getReasonPhrase());
        errorBody.put("path", path);
        errorBody.put("code", code);
        errorBody.put("message", message);
        errorBody.put("fieldErrors", Map.of());

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorBody);
        } catch (JsonProcessingException e) {
            log.error("gateway failed to serialize rate limit error response", e);
            return Mono.error(e);
        }
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);

        log.info("gateway rate limit rejected method={} path={} routeId={} status={} durationMs={}",
                requestMethod(exchange),
                path,
                routeId(exchange),
                status.value(),
                System.currentTimeMillis() - startTime);

        return response.writeWith(Mono.just(buffer));
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