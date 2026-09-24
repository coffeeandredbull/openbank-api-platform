package com.openbank.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.gateway.auth.InvalidJwtException;
import com.openbank.gateway.auth.JwtIdentity;
import com.openbank.gateway.auth.JwtTokenService;
import com.openbank.gateway.auth.TrustedIdentityHeaders;
import com.openbank.gateway.ratelimit.RuntimeApiPath;
import com.openbank.gateway.revocation.TokenRevocationService;
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
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String MESSAGE_UNAUTHENTICATED = "Authentication is required";
    private static final String CODE_REVOKED = "TOKEN_REVOKED";
    private static final String MESSAGE_REVOKED = "Token has been revoked";
    private static final String CODE_REVOCATION_UNAVAILABLE = "TOKEN_REVOCATION_SERVICE_UNAVAILABLE";
    private static final String MESSAGE_REVOCATION_UNAVAILABLE = "Token revocation service unavailable";

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
    private final TrustedIdentityHeaderSanitizer trustedIdentityHeaderSanitizer;
    private final TokenRevocationService tokenRevocationService;

    public JwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            ObjectMapper objectMapper,
            TrustedIdentityHeaderSanitizer trustedIdentityHeaderSanitizer,
            TokenRevocationService tokenRevocationService) {
        this.jwtTokenService = jwtTokenService;
        this.objectMapper = objectMapper;
        this.trustedIdentityHeaderSanitizer = trustedIdentityHeaderSanitizer;
        this.tokenRevocationService = tokenRevocationService;
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
        String token = bearerToken(exchange);
        if (token == null) {
            return reject(exchange, method, path, startTime,
                    HttpStatus.UNAUTHORIZED, CODE_UNAUTHENTICATED, MESSAGE_UNAUTHENTICATED);
        }
        JwtTokenService.VerifiedJwt verified;
        try {
            verified = jwtTokenService.verify(token);
        } catch (InvalidJwtException e) {
            return reject(exchange, method, path, startTime,
                    HttpStatus.UNAUTHORIZED, CODE_UNAUTHENTICATED, MESSAGE_UNAUTHENTICATED);
        }
        JwtIdentity identity = verified.identity();
        exchange.getAttributes().put(IDENTITY_ATTRIBUTE, identity);
        if (verified.jti() != null) {
            return Mono.fromCallable(() -> tokenRevocationService.check(verified.jti()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(status -> switch (status) {
                        case REVOKED -> reject(exchange, method, path, startTime,
                                HttpStatus.UNAUTHORIZED, CODE_REVOKED, MESSAGE_REVOKED);
                        case UNAVAILABLE -> reject(exchange, method, path, startTime,
                                HttpStatus.SERVICE_UNAVAILABLE, CODE_REVOCATION_UNAVAILABLE,
                                MESSAGE_REVOCATION_UNAVAILABLE);
                        case ACTIVE -> forward(exchange, chain, identity, path);
                    });
        }
        return forward(exchange, chain, identity, path);
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, JwtIdentity identity, String path) {
        if (!RuntimeApiPath.isRuntimePath(path)) {
            return chain.filter(exchange);
        }
        return chain.filter(withTrustedJwtIdentity(exchange, identity));
    }

    private ServerWebExchange withTrustedJwtIdentity(
            ServerWebExchange exchange, JwtIdentity identity) {
        ServerWebExchange sanitized = trustedIdentityHeaderSanitizer.sanitize(exchange);
        ServerHttpRequestDecorator decorated = new ServerHttpRequestDecorator(sanitized.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.set(TrustedIdentityHeaders.USER_ID, String.valueOf(identity.userId()));
                headers.set(TrustedIdentityHeaders.ROLES, identity.role().name());
                return HttpHeaders.readOnlyHttpHeaders(headers);
            }
        };
        return sanitized.mutate().request(decorated).build();
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

    private String bearerToken(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        return token;
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpMethod method, String path, long startTime,
            HttpStatus status, String code, String message) {
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
            log.error("gateway failed to serialize authentication error response", e);
            return Mono.error(e);
        }
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);

        log.info("gateway auth rejected method={} path={} routeId={} status={} durationMs={}",
                method != null ? method.name() : "unknown",
                path,
                routeId(exchange),
                status.value(),
                System.currentTimeMillis() - startTime);

        return response.writeWith(Mono.just(buffer));
    }

    private String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null ? route.getId() : "none";
    }
}