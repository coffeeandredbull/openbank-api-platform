package com.openbank.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.gateway.auth.InvalidJwtException;
import com.openbank.gateway.auth.JwtIdentity;
import com.openbank.gateway.auth.JwtTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    public static final String IDENTITY_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".identity";

    private static final Set<String> PROTECTED_PATH_PREFIXES = Set.of(
            "/runtime",
            "/apis",
            "/applications",
            "/subscriptions",
            "/credentials",
            "/accounts",
            "/payments",
            "/transactions"
    );

    private final JwtTokenService jwtTokenService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, ObjectMapper objectMapper) {
        this.jwtTokenService = jwtTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return -200;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getPath().value();

        if (exchange.getAttribute(ClientCredentialAuthenticationFilter.CLIENT_CREDENTIAL_ATTRIBUTE) != null) {
            return chain.filter(exchange);
        }
        if (isPublic(method, path) || !isProtected(path)) {
            return chain.filter(exchange);
        }
        JwtIdentity identity = identityFrom(exchange);
        if (identity == null) {
            return reject(exchange, method, path, startTime);
        }
        exchange.getAttributes().put(IDENTITY_ATTRIBUTE, identity);
        return chain.filter(exchange);
    }

    private boolean isPublic(HttpMethod method, String path) {
        if (method == null) {
            return false;
        }
        if (method == HttpMethod.POST && (isPath(path, "/users") || isPath(path, "/auth/login"))) {
            return true;
        }
        return method == HttpMethod.GET && isPath(path, "/actuator/health");
    }

    private boolean isProtected(String path) {
        if (isPath(path, "/users") || path.startsWith("/users/")) {
            return true;
        }
        if (isPath(path, "/auth") || path.startsWith("/auth/")) {
            return true;
        }
        return PROTECTED_PATH_PREFIXES.stream()
                .anyMatch(prefix -> isPath(path, prefix) || path.startsWith(prefix + "/"));
    }

    private boolean isPath(String path, String expected) {
        return path.equals(expected);
    }

    private JwtIdentity identityFrom(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        try {
            return jwtTokenService.validateToken(token);
        } catch (InvalidJwtException e) {
            return null;
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpMethod method, String path, long startTime) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("timestamp", Instant.now().toString());
        errorBody.put("status", HttpStatus.UNAUTHORIZED.value());
        errorBody.put("error", HttpStatus.UNAUTHORIZED.getReasonPhrase());
        errorBody.put("path", path);
        errorBody.put("code", "UNAUTHENTICATED");
        errorBody.put("message", "Authentication is required");
        errorBody.put("fieldErrors", Map.of());

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorBody);
        } catch (JsonProcessingException e) {
            log.error("gateway failed to serialize authentication error response", e);
            return Mono.error(e);
        }
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);

        log.info("gateway auth rejected method={} path={} routeId={} status={} durationMs={}",
                method != null ? method.name() : "unknown",
                path,
                routeId(exchange),
                HttpStatus.UNAUTHORIZED.value(),
                System.currentTimeMillis() - startTime);

        return response.writeWith(Mono.just(buffer));
    }

    private String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null ? route.getId() : "none";
    }
}