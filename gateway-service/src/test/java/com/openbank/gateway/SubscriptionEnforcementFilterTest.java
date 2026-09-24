package com.openbank.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.gateway.auth.ClientCredentialIdentity;
import com.openbank.gateway.auth.JwtIdentity;
import com.openbank.gateway.auth.UserRole;
import com.openbank.gateway.filter.ClientCredentialAuthenticationFilter;
import com.openbank.gateway.filter.JwtAuthenticationFilter;
import com.openbank.gateway.filter.RateLimitingFilter;
import com.openbank.gateway.filter.SubscriptionEnforcementFilter;
import com.openbank.gateway.ratelimit.RateLimitPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionEnforcementFilterTest {

    record Result(boolean forwarded, HttpStatusCode status, String body) {
    }

    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>("{\"subscribed\":false}");
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final AtomicReference<Map<String, String>> LAST_QUERY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicBoolean HIT = new AtomicBoolean(false);

    private static final HttpServer CHECK_SERVER = startServer();

    private SubscriptionEnforcementFilter filter;

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handle(exchange));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start subscription check server", e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        HIT.set(true);
        LAST_QUERY.set(parseQuery(exchange.getRequestURI().getRawQuery()));
        LAST_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        byte[] bytes = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(RESPONSE_STATUS.get(), bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2) {
                    params.put(parts[0], parts[1]);
                }
            }
        }
        return params;
    }

    @AfterAll
    static void stopServer() {
        CHECK_SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        RESPONSE_BODY.set(
                "{\"subscribed\":true,\"tierId\":1,\"tierName\":\"Gold\","
                        + "\"requestsPerWindow\":100,\"windowSeconds\":60}");
        RESPONSE_STATUS.set(200);
        LAST_QUERY.set(null);
        LAST_AUTHORIZATION.set(null);
        HIT.set(false);
        filter = new SubscriptionEnforcementFilter(
                "http://127.0.0.1:" + CHECK_SERVER.getAddress().getPort(),
                new ObjectMapper());
    }

    private Result call(String path) {
        return call(path, true);
    }

    private Result call(String path, boolean authenticated) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        if (authenticated) {
            exchange.getAttributes().put(
                    JwtAuthenticationFilter.IDENTITY_ATTRIBUTE,
                    new JwtIdentity(1L, UserRole.ADMIN));
        }
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        String body = "";
        if (status != null && (status.value() == 403 || status.value() == 503)) {
            body = exchange.getResponse().getBodyAsString().block();
        }
        return new Result(forwarded.get(), status, body);
    }

    @Test
    void subscribedRequestIsForwarded() {
        Result result = call("/runtime/apis/payments/v1/accounts");
        assertThat(HIT).isTrue();
        assertThat(result.forwarded()).isTrue();
        assertThat(LAST_QUERY.get()).containsEntry("contextPath", "/payments");
        assertThat(LAST_QUERY.get()).containsEntry("version", "v1");
    }

    @Test
    void allowedRequestStoresTheRateLimitPolicyOnTheExchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts").build());
        exchange.getAttributes().put(
                JwtAuthenticationFilter.IDENTITY_ATTRIBUTE,
                new JwtIdentity(1L, UserRole.ADMIN));
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(forwarded).isTrue();
        Object policy = exchange.getAttribute(
                RateLimitingFilter.RATE_LIMIT_POLICY_ATTRIBUTE);
        assertThat(policy).isEqualTo(new RateLimitPolicy(100, 60));
    }

    @Test
    void subscribedResponseWithoutRateLimitPolicyFailsClosedAs503() {
        RESPONSE_BODY.set("{\"subscribed\":true}");

        Result result = call("/runtime/apis/payments/v1/accounts");
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.body()).contains("\"code\":\"SUBSCRIPTION_SERVICE_UNAVAILABLE\"");
    }

    @Test
    void authorizationHeaderIsForwardedToTheCheckEndpoint() {
        String token = GatewayTestJwt.admin();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());
        exchange.getAttributes().put(
                JwtAuthenticationFilter.IDENTITY_ATTRIBUTE,
                new JwtIdentity(1L, UserRole.ADMIN));
        GatewayFilterChain chain = ex -> Mono.empty();
        filter.filter(exchange, chain).block();
        assertThat(LAST_AUTHORIZATION.get()).isEqualTo("Bearer " + token);
    }

    @Test
    void unsubscribedRequestReturns403AndIsNotForwarded() {
        RESPONSE_BODY.set("{\"subscribed\":false}");
        Result result = call("/runtime/apis/payments/v1/accounts");
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.body()).contains("\"status\":403");
        assertThat(result.body()).contains("\"code\":\"SUBSCRIPTION_REQUIRED\"");
        assertThat(result.body())
                .contains("\"message\":\"An active subscription is required to access this API\"");
        assertThat(result.body()).contains("\"path\":\"/runtime/apis/payments/v1/accounts\"");
        assertThat(result.body()).contains("\"fieldErrors\":{}");
        assertThat(result.body()).doesNotContain("SUBSCRIPTION_SERVICE_UNAVAILABLE");
    }

    @Test
    void malformedTwoHundredResponseFailsClosed() {
        RESPONSE_BODY.set("{\"subscribed\":\"yes\"}");

        Result result = call("/runtime/apis/payments/v1/accounts");
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.body()).contains("\"code\":\"SUBSCRIPTION_REQUIRED\"");

        RESPONSE_BODY.set("not-json-at-all");
        Result garbage = call("/runtime/apis/payments/v1/accounts");
        assertThat(garbage.forwarded()).isFalse();
        assertThat(garbage.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unexpectedFourHundredFromCheckReturns403() {
        RESPONSE_STATUS.set(401);
        Result result = call("/runtime/apis/payments/v1/accounts");
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.body()).contains("\"code\":\"SUBSCRIPTION_REQUIRED\"");
    }

    @Test
    void checkServiceFiveHundredReturns503AndIsNotForwarded() {
        RESPONSE_STATUS.set(500);
        Result result = call("/runtime/apis/payments/v1/accounts");
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.body()).contains("\"status\":503");
        assertThat(result.body()).contains("\"code\":\"SUBSCRIPTION_SERVICE_UNAVAILABLE\"");
        assertThat(result.body())
                .contains("\"message\":\"Subscription verification is temporarily unavailable\"");
        assertThat(result.body()).contains("\"path\":\"/runtime/apis/payments/v1/accounts\"");
        assertThat(result.body()).doesNotContain("127.0.0.1").doesNotContain("localhost");
    }

    @Test
    void unreachableCheckServiceReturns503() {
        String unreachableUrl = "http://127.0.0.1:" + freePort();
        SubscriptionEnforcementFilter unreachableFilter =
                new SubscriptionEnforcementFilter(unreachableUrl, new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts"));
        exchange.getAttributes().put(
                JwtAuthenticationFilter.IDENTITY_ATTRIBUTE,
                new JwtIdentity(1L, UserRole.ADMIN));
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        unreachableFilter.filter(exchange, chain).block();
        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"SUBSCRIPTION_SERVICE_UNAVAILABLE\"");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("failed to allocate a free port", e);
        }
    }

    @Test
    void nonRuntimePathsAreForwardedWithoutContactingTheCheckService() {
        Result result = call("/apis/list");
        assertThat(result.forwarded()).isTrue();
        assertThat(HIT).isFalse();
    }

    @Test
    void runtimePathWithoutIdentityFailsClosed() {
        Result result = call("/runtime/apis/payments/v1/accounts", false);
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.body()).contains("\"code\":\"SUBSCRIPTION_SERVICE_UNAVAILABLE\"");
    }

    @Test
    void runtimePathWithoutVersionFailsClosedWithoutChecking() {
        Result result = call("/runtime/apis/payments");
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(HIT).isFalse();
    }

    @Test
    void rejectedResponseDoesNotExposeTheJwt() {
        RESPONSE_BODY.set("{\"subscribed\":false}");
        String token = GatewayTestJwt.admin();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());
        exchange.getAttributes().put(
                JwtAuthenticationFilter.IDENTITY_ATTRIBUTE,
                new JwtIdentity(1L, UserRole.ADMIN));
        GatewayFilterChain chain = ex -> Mono.empty();
        filter.filter(exchange, chain).block();
        assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain(token);
    }

    @Test
    void clientCredentialRequestWithStoredSubscriptionIsForwardedWithoutContactingTheCheckService() {
        MockServerWebExchange exchange = clientCredentialExchange(
                "/runtime/apis/payments/v1/accounts", true);
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(forwarded).isTrue();
        assertThat(HIT).isFalse();
    }

    @Test
    void clientCredentialRequestWithoutAStoredSubscriptionIsRejectedWith403() {
        MockServerWebExchange exchange = clientCredentialExchange(
                "/runtime/apis/payments/v1/accounts", false);
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(forwarded).isFalse();
        assertThat(HIT).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"SUBSCRIPTION_REQUIRED\"");
    }

    @Test
    void clientCredentialRequestOnMalformedRuntimePathIsRejectedWith403() {
        MockServerWebExchange exchange = clientCredentialExchange("/runtime/apis/payments", true);
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(forwarded).isFalse();
        assertThat(HIT).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void clientCredentialRequestWithUnknownSubscriptionStateFailsClosed() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts").build());
        exchange.getAttributes().put(ClientCredentialAuthenticationFilter.CLIENT_CREDENTIAL_ATTRIBUTE,
                new ClientCredentialIdentity("client-abc", 12L, 42L));
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(HIT).isFalse();
    }

    private MockServerWebExchange clientCredentialExchange(String path, boolean subscribed) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        exchange.getAttributes().put(ClientCredentialAuthenticationFilter.CLIENT_CREDENTIAL_ATTRIBUTE,
                new ClientCredentialIdentity("client-abc", 12L, 42L));
        exchange.getAttributes().put(
                ClientCredentialAuthenticationFilter.APPLICATION_SUBSCRIBED_ATTRIBUTE, subscribed);
        return exchange;
    }
}