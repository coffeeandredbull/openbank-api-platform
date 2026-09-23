package com.openbank.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class SubscriptionEnforcementFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionEnforcementFilter.class);

    private static final String RUNTIME_API_PATH = "/runtime/apis";
    private static final String INTERNAL_CHECK_PATH = "/internal/subscription-check";
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;

    private static final String CODE_REQUIRED = "SUBSCRIPTION_REQUIRED";
    private static final String MESSAGE_REQUIRED = "An active subscription is required to access this API";
    private static final String CODE_UNAVAILABLE = "SUBSCRIPTION_SERVICE_UNAVAILABLE";
    private static final String MESSAGE_UNAVAILABLE = "Subscription verification is temporarily unavailable";

    private final ObjectMapper objectMapper;
    private final WebClient checkClient;

    public SubscriptionEnforcementFilter(
            @Value("${API_MANAGEMENT_SERVICE_URL:http://localhost:8081}") String apiManagementServiceUrl,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(RESPONSE_TIMEOUT);
        this.checkClient = WebClient.builder()
                .baseUrl(apiManagementServiceUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public int getOrder() {
        return -150;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!isRuntimeApiPath(path)) {
            return chain.filter(exchange);
        }
        long startTime = System.currentTimeMillis();
        if (exchange.getAttribute(ClientCredentialAuthenticationFilter.CLIENT_CREDENTIAL_ATTRIBUTE) != null) {
            return enforceForApplication(exchange, chain, path, startTime);
        }
        if (exchange.getAttribute(JwtAuthenticationFilter.IDENTITY_ATTRIBUTE) == null) {
            return writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, CODE_UNAVAILABLE,
                    MESSAGE_UNAVAILABLE, path, startTime);
        }
        ContextVersion target = parse(path);
        if (target == null) {
            return writeError(exchange, HttpStatus.FORBIDDEN, CODE_REQUIRED,
                    MESSAGE_REQUIRED, path, startTime);
        }
        return check(exchange, target)
                .flatMap(result -> switch (result) {
                    case ALLOWED -> {
                        log.info("gateway subscription check allowed method={} path={} routeId={} durationMs={}",
                                requestMethod(exchange),
                                path,
                                routeId(exchange),
                                System.currentTimeMillis() - startTime);
                        yield chain.filter(exchange);
                    }
                    case DENIED -> writeError(exchange, HttpStatus.FORBIDDEN, CODE_REQUIRED,
                            MESSAGE_REQUIRED, path, startTime);
                    case UNAVAILABLE -> writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, CODE_UNAVAILABLE,
                            MESSAGE_UNAVAILABLE, path, startTime);
                });
    }

    private Mono<Void> enforceForApplication(
            ServerWebExchange exchange, GatewayFilterChain chain, String path, long startTime) {
        ContextVersion target = parse(path);
        Boolean subscribed = exchange.getAttribute(
                ClientCredentialAuthenticationFilter.APPLICATION_SUBSCRIBED_ATTRIBUTE);
        if (target == null || !Boolean.TRUE.equals(subscribed)) {
            return writeError(exchange, HttpStatus.FORBIDDEN, CODE_REQUIRED,
                    MESSAGE_REQUIRED, path, startTime);
        }
        log.info("gateway subscription check allowed method={} path={} routeId={} durationMs={}",
                requestMethod(exchange),
                path,
                routeId(exchange),
                System.currentTimeMillis() - startTime);
        return chain.filter(exchange);
    }

    private boolean isRuntimeApiPath(String path) {
        return path.equals(RUNTIME_API_PATH) || path.startsWith(RUNTIME_API_PATH + "/");
    }

    private ContextVersion parse(String path) {
        if (!path.startsWith(RUNTIME_API_PATH + "/")) {
            return null;
        }
        String remainder = path.substring(RUNTIME_API_PATH.length() + 1);
        String[] segments = remainder.split("/");
        if (segments.length < 2) {
            return null;
        }
        String context = segments[0];
        String version = segments[1];
        if (context.isEmpty() || version.isEmpty()) {
            return null;
        }
        return new ContextVersion("/" + context, version);
    }

    private Mono<CheckResult> check(ServerWebExchange exchange, ContextVersion target) {
        WebClient.RequestHeadersSpec<?> request = checkClient.get()
                .uri(builder -> builder.path(INTERNAL_CHECK_PATH)
                        .queryParam("contextPath", target.contextPath())
                        .queryParam("version", target.version())
                        .build());
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchangeToMono(response -> {
            int status = response.statusCode().value();
            if (status >= 500) {
                return response.bodyToMono(String.class).then(Mono.just(CheckResult.UNAVAILABLE));
            }
            if (status >= 400) {
                return response.bodyToMono(String.class).then(Mono.just(CheckResult.DENIED));
            }
            return response.bodyToMono(String.class)
                    .map(body -> isSubscribed(body) ? CheckResult.ALLOWED : CheckResult.DENIED)
                    .defaultIfEmpty(CheckResult.DENIED);
        })
                .timeout(RESPONSE_TIMEOUT)
                .onErrorResume(throwable -> Mono.just(isTransportFailure(throwable)
                        ? CheckResult.UNAVAILABLE
                        : CheckResult.DENIED));
    }

    private boolean isSubscribed(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.isObject() && node.hasNonNull("subscribed") && node.path("subscribed").asBoolean();
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private boolean isTransportFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof IOException || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> writeError(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message,
            String path,
            long startTime) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

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
            log.error("gateway failed to serialize subscription error response", e);
            return Mono.error(e);
        }
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);

        log.info("gateway subscription check rejected method={} path={} routeId={} status={} durationMs={}",
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

    private record ContextVersion(String contextPath, String version) {
    }

    private enum CheckResult {
        ALLOWED, DENIED, UNAVAILABLE
    }
}