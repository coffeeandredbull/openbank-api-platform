package com.openbank.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.gateway.auth.ClientCredentialIdentity;
import com.openbank.gateway.auth.TrustedIdentityHeaders;
import com.openbank.gateway.ratelimit.RuntimeApiPath;
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
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ClientCredentialAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ClientCredentialAuthenticationFilter.class);

    public static final String CLIENT_CREDENTIAL_ATTRIBUTE =
            ClientCredentialAuthenticationFilter.class.getName() + ".identity";
    public static final String APPLICATION_SUBSCRIBED_ATTRIBUTE =
            ClientCredentialAuthenticationFilter.class.getName() + ".subscribed";

    private static final String INTERNAL_CHECK_PATH = "/internal/credential-check";
    private static final String BASIC_PREFIX = "Basic ";

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);
    private static final int CONNECT_TIMEOUT_MILLIS = 2000;

    private static final String CODE_INVALID = "CLIENT_CREDENTIAL_INVALID";
    private static final String MESSAGE_INVALID = "Invalid client credentials";
    private static final String CODE_UNAVAILABLE = "CREDENTIAL_SERVICE_UNAVAILABLE";
    private static final String MESSAGE_UNAVAILABLE = "Credential verification is temporarily unavailable";
    private static final String CODE_SUBSCRIPTION_REQUIRED = "SUBSCRIPTION_REQUIRED";
    private static final String MESSAGE_SUBSCRIPTION_REQUIRED =
            "An active subscription is required to access this API";

    private final ObjectMapper objectMapper;
    private final WebClient checkClient;
    private final TrustedIdentityHeaderSanitizer trustedIdentityHeaderSanitizer;

    public ClientCredentialAuthenticationFilter(
            ObjectMapper objectMapper,
            TrustedIdentityHeaderSanitizer trustedIdentityHeaderSanitizer,
            @Value("${API_MANAGEMENT_SERVICE_URL:http://localhost:8081}") String apiManagementServiceUrl) {
        this.objectMapper = objectMapper;
        this.trustedIdentityHeaderSanitizer = trustedIdentityHeaderSanitizer;
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
        return -210;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!RuntimeApiPath.isRuntimePath(path) || !isBasicAuthorization(authorization)) {
            return chain.filter(exchange);
        }
        long startTime = System.currentTimeMillis();
        RuntimeApiPath target = RuntimeApiPath.from(path).orElse(null);
        if (target == null) {
            return writeError(exchange, path, HttpStatus.FORBIDDEN, CODE_SUBSCRIPTION_REQUIRED,
                    MESSAGE_SUBSCRIPTION_REQUIRED, startTime);
        }
        return check(exchange, target)
                .flatMap(outcome -> switch (outcome.state()) {
                    case AUTHENTICATED -> {
                        BasicCredentials basic = parseBasic(authorization);
                        ClientCredentialIdentity identity = new ClientCredentialIdentity(
                                basic.clientId(), outcome.applicationId(), outcome.ownerUserId());
                        exchange.getAttributes().put(CLIENT_CREDENTIAL_ATTRIBUTE, identity);
                        exchange.getAttributes().put(APPLICATION_SUBSCRIBED_ATTRIBUTE, outcome.subscribed());
                        log.info("gateway client credentials accepted applicationId={} method={} path={} "
                                        + "routeId={} durationMs={}",
                                outcome.applicationId(),
                                requestMethod(exchange),
                                path,
                                routeId(exchange),
                                System.currentTimeMillis() - startTime);
                        yield chain.filter(withTrustedClientCredentialIdentity(exchange, identity));
                    }
                    case INVALID -> writeError(exchange, path, HttpStatus.UNAUTHORIZED,
                            CODE_INVALID, MESSAGE_INVALID, startTime);
                    case UNAVAILABLE -> writeError(exchange, path, HttpStatus.SERVICE_UNAVAILABLE,
                            CODE_UNAVAILABLE, MESSAGE_UNAVAILABLE, startTime);
                });
    }

    private boolean isBasicAuthorization(String authorization) {
        return authorization != null
                && authorization.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length());
    }

    private Mono<CheckOutcome> check(ServerWebExchange exchange, RuntimeApiPath target) {
        BasicCredentials basic = parseBasic(
                exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (basic == null) {
            return Mono.just(CheckOutcome.invalid());
        }
        return checkClient.get()
                .uri(builder -> builder.path(INTERNAL_CHECK_PATH)
                        .queryParam("contextPath", target.contextPath())
                        .queryParam("version", target.version())
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic.base64())
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status >= 500) {
                        return response.bodyToMono(String.class).then(Mono.just(CheckOutcome.unavailable()));
                    }
                    if (status >= 400) {
                        return response.bodyToMono(String.class).then(Mono.just(CheckOutcome.invalid()));
                    }
                    return response.bodyToMono(String.class)
                            .map(this::parseAuthenticated)
                            .defaultIfEmpty(CheckOutcome.unavailable())
                            .onErrorReturn(CheckOutcome.unavailable());
                })
                .timeout(RESPONSE_TIMEOUT)
                .onErrorReturn(CheckOutcome.unavailable());
    }

    private CheckOutcome parseAuthenticated(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!node.isObject() || !node.path("authenticated").asBoolean(false)) {
                return CheckOutcome.invalid();
            }
            if (!node.path("applicationId").isIntegralNumber()
                    || !node.path("ownerUserId").isIntegralNumber()) {
                return CheckOutcome.unavailable();
            }
            boolean subscribed = node.path("subscribed").asBoolean(false);
            return new CheckOutcome(
                    CheckState.AUTHENTICATED,
                    node.path("applicationId").asLong(),
                    node.path("ownerUserId").asLong(),
                    subscribed);
        } catch (JsonProcessingException e) {
            return CheckOutcome.unavailable();
        }
    }

    private BasicCredentials parseBasic(String authorization) {
        String encoded = authorization.substring(BASIC_PREFIX.length()).trim();
        if (encoded.isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator <= 0 || separator == decoded.length() - 1) {
                return null;
            }
            return new BasicCredentials(decoded.substring(0, separator), encoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ServerWebExchange withTrustedClientCredentialIdentity(
            ServerWebExchange exchange, ClientCredentialIdentity identity) {
        ServerWebExchange sanitized = trustedIdentityHeaderSanitizer.sanitize(exchange);
        ServerHttpRequestDecorator decorated = new ServerHttpRequestDecorator(sanitized.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.remove(HttpHeaders.AUTHORIZATION);
                headers.set(TrustedIdentityHeaders.USER_ID, String.valueOf(identity.ownerUserId()));
                headers.set(TrustedIdentityHeaders.APPLICATION_ID, String.valueOf(identity.applicationId()));
                headers.set(TrustedIdentityHeaders.CLIENT_ID, identity.clientId());
                return HttpHeaders.readOnlyHttpHeaders(headers);
            }
        };
        return sanitized.mutate().request(decorated).build();
    }

    private Mono<Void> writeError(
            ServerWebExchange exchange,
            String path,
            HttpStatus status,
            String code,
            String message,
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
            log.error("gateway failed to serialize credential error response", e);
            return Mono.error(e);
        }
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);

        log.info("gateway client credentials rejected method={} path={} routeId={} status={} durationMs={}",
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

    private enum CheckState {
        AUTHENTICATED, INVALID, UNAVAILABLE
    }

    private record CheckOutcome(
            CheckState state, long applicationId, long ownerUserId, boolean subscribed) {

        static CheckOutcome invalid() {
            return new CheckOutcome(CheckState.INVALID, 0, 0, false);
        }

        static CheckOutcome unavailable() {
            return new CheckOutcome(CheckState.UNAVAILABLE, 0, 0, false);
        }
    }

    private record BasicCredentials(String clientId, String base64) {
    }
}