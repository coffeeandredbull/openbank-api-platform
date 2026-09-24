package com.openbank.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.gateway.auth.ClientCredentialIdentity;
import com.openbank.gateway.auth.TrustedIdentityHeaders;
import com.openbank.gateway.filter.ClientCredentialAuthenticationFilter;
import com.openbank.gateway.filter.RateLimitingFilter;
import com.openbank.gateway.filter.TrustedIdentityHeaderSanitizer;
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
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ClientCredentialAuthenticationFilterTest {

    record Result(
            boolean forwarded,
            HttpStatusCode status,
            String body,
            ServerWebExchange downstream) {
    }

    private static final String AUTHENTICATED_BODY = "{\"authenticated\":true,\"applicationId\":7,\"ownerUserId\":42,"
            + "\"subscribed\":true,\"tierId\":1,\"tierName\":\"Gold\","
            + "\"requestsPerWindow\":100,\"windowSeconds\":60}";

    private static final AtomicReference<String> RESPONSE_BODY =
            new AtomicReference<>(AUTHENTICATED_BODY);
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final AtomicReference<Map<String, String>> LAST_QUERY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicBoolean HIT = new AtomicBoolean(false);

    private static final HttpServer CHECK_SERVER = startServer();

    private static final String CLIENT_ID = "client-abc-1234";
    private static final String CLIENT_SECRET = "super-secret-value";

    private ClientCredentialAuthenticationFilter filter;

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handle(exchange));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start credential check server", e);
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
        RESPONSE_BODY.set(AUTHENTICATED_BODY);
        RESPONSE_STATUS.set(200);
        LAST_QUERY.set(null);
        LAST_AUTHORIZATION.set(null);
        HIT.set(false);
        filter = new ClientCredentialAuthenticationFilter(
                new ObjectMapper(),
                new TrustedIdentityHeaderSanitizer(),
                "http://127.0.0.1:" + CHECK_SERVER.getAddress().getPort());
    }

    private Result call(String path) {
        return call(path, basicHeader());
    }

    private Result call(String path, String authorization) {
        return call(path, authorization, Map.of());
    }

    private Result call(String path, String authorization, Map<String, String> extraHeaders) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        extraHeaders.forEach(builder::header);
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        AtomicBoolean forwarded = new AtomicBoolean(false);
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            downstream.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        String body = "";
        if (status != null && status.isError()) {
            body = exchange.getResponse().getBodyAsString().block();
        }
        return new Result(forwarded.get(), status, body, downstream.get());
    }

    private String basicHeader() {
        return basicHeader(CLIENT_ID, CLIENT_SECRET);
    }

    private String basicHeader(String clientId, String clientSecret) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validClientCredentialsAuthenticateAndForwardTheRequestWithoutAuthorizationHeader() {
        Result result = call("/runtime/apis/payments/v1/accounts");

        assertThat(HIT).isTrue();
        assertThat(LAST_QUERY.get()).containsEntry("contextPath", "/payments");
        assertThat(LAST_QUERY.get()).containsEntry("version", "v1");
        assertThat(LAST_AUTHORIZATION.get()).isEqualTo(basicHeader());
        assertThat(result.forwarded()).isTrue();
        assertThat(result.status()).isNull();
        assertThat(result.downstream().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void authenticatedIdentitySubscriptionAndRateLimitPolicyAreStoredAsExchangeAttributes() {
        Result result = call("/runtime/apis/payments/v1/accounts");

        ServerWebExchange downstream = result.downstream();
        Object identity = downstream.getAttribute(
                ClientCredentialAuthenticationFilter.CLIENT_CREDENTIAL_ATTRIBUTE);
        Object subscribed = downstream.getAttribute(
                ClientCredentialAuthenticationFilter.APPLICATION_SUBSCRIBED_ATTRIBUTE);
        Object policy = downstream.getAttribute(
                RateLimitingFilter.RATE_LIMIT_POLICY_ATTRIBUTE);
        assertThat(identity)
                .isEqualTo(new ClientCredentialIdentity(CLIENT_ID, 7L, 42L));
        assertThat(subscribed)
                .isEqualTo(Boolean.TRUE);
        assertThat(policy)
                .isEqualTo(new RateLimitPolicy(100, 60));
    }

    @Test
    void unsubscribedApplicationIsAuthenticatedButTheSubscriptionFlagIsCarried() {
        RESPONSE_BODY.set(
                "{\"authenticated\":true,\"applicationId\":7,\"ownerUserId\":42,\"subscribed\":false}");

        Result result = call("/runtime/apis/payments/v1/accounts");

        assertThat(result.forwarded()).isTrue();
        assertThat(result.status()).isNull();
        Object subscribed = result.downstream().getAttribute(
                ClientCredentialAuthenticationFilter.APPLICATION_SUBSCRIBED_ATTRIBUTE);
        assertThat(subscribed)
                .isEqualTo(Boolean.FALSE);
        Object policy = result.downstream().getAttribute(
                RateLimitingFilter.RATE_LIMIT_POLICY_ATTRIBUTE);
        assertThat(policy).isNull();
    }

    @Test
    void subscribedResponseWithoutRateLimitPolicyFailsClosedAs503() {
        RESPONSE_BODY.set(
                "{\"authenticated\":true,\"applicationId\":7,\"ownerUserId\":42,\"subscribed\":true}");

        Result result = call("/runtime/apis/payments/v1/accounts");

        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.body()).contains("\"code\":\"CREDENTIAL_SERVICE_UNAVAILABLE\"");
    }

    @Test
    void rejectedClientCredentialsReturn401AndAreNotForwarded() {
        RESPONSE_STATUS.set(401);
        RESPONSE_BODY.set("{\"authenticated\":false}");

        Result result = call("/runtime/apis/payments/v1/accounts");

        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(result.body())
                .contains("\"status\":401")
                .contains("\"code\":\"CLIENT_CREDENTIAL_INVALID\"")
                .contains("\"message\":\"Invalid client credentials\"")
                .contains("\"path\":\"/runtime/apis/payments/v1/accounts\"")
                .contains("\"fieldErrors\":{}");
    }

    @Test
    void checkServiceFiveHundredReturns503AndIsNotForwarded() {
        RESPONSE_STATUS.set(500);

        Result result = call("/runtime/apis/payments/v1/accounts");

        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.body())
                .contains("\"status\":503")
                .contains("\"code\":\"CREDENTIAL_SERVICE_UNAVAILABLE\"")
                .contains("\"message\":\"Credential verification is temporarily unavailable\"");
    }

    @Test
    void unreachableCheckServiceReturns503() {
        String unreachableUrl = "http://127.0.0.1:" + freePort();
        ClientCredentialAuthenticationFilter unreachableFilter =
                new ClientCredentialAuthenticationFilter(
                        new ObjectMapper(),
                        new TrustedIdentityHeaderSanitizer(),
                        unreachableUrl);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/runtime/apis/payments/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, basicHeader())
                        .build());
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            forwarded.set(true);
            return Mono.empty();
        };
        unreachableFilter.filter(exchange, chain).block();
        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"CREDENTIAL_SERVICE_UNAVAILABLE\"");
    }

    @Test
    void malformedBasicHeaderOnARuntimePathReturns401WithoutContactingTheService() {
        Result result = call("/runtime/apis/payments/v1/accounts", "Basic !!!not-base64!!!");

        assertThat(HIT).isFalse();
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(result.body()).contains("\"code\":\"CLIENT_CREDENTIAL_INVALID\"");
    }

    @Test
    void malformedRuntimePathWithBasicCredentialsReturns403() {
        Result result = call("/runtime/apis/payments");

        assertThat(HIT).isFalse();
        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.body())
                .contains("\"code\":\"SUBSCRIPTION_REQUIRED\"")
                .contains("\"message\":\"An active subscription is required to access this API\"");
    }

    @Test
    void nonRuntimePathWithBasicCredentialsIsNotIntercepted() {
        Result result = call("/accounts/1");

        assertThat(HIT).isFalse();
        assertThat(result.forwarded()).isTrue();
        assertThat(result.status()).isNull();
        assertThat(result.downstream().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo(basicHeader());
    }

    @Test
    void bearerAuthenticationOnARuntimePathIsNotIntercepted() {
        Result result = call("/runtime/apis/payments/v1/accounts", "Bearer some-token");

        assertThat(HIT).isFalse();
        assertThat(result.forwarded()).isTrue();
    }

    @Test
    void missingAuthorizationHeaderOnARuntimePathIsNotIntercepted() {
        Result result = call("/runtime/apis/payments/v1/accounts", null);

        assertThat(HIT).isFalse();
        assertThat(result.forwarded()).isTrue();
    }

    @Test
    void malformedSuccessBodyFailsClosedAs503() {
        RESPONSE_BODY.set("not-json-at-all");

        Result result = call("/runtime/apis/payments/v1/accounts");

        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.body()).contains("\"code\":\"CREDENTIAL_SERVICE_UNAVAILABLE\"");
    }

    @Test
    void successBodyWithoutIdentifierFieldsFailsClosedAs503() {
        RESPONSE_BODY.set("{\"authenticated\":true,\"subscribed\":true}");

        Result result = call("/runtime/apis/payments/v1/accounts");

        assertThat(result.forwarded()).isFalse();
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void errorResponsesDoNotExposeTheClientSecret() {
        RESPONSE_STATUS.set(401);
        Result rejected = call("/runtime/apis/payments/v1/accounts");
        assertThat(rejected.body())
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain(CLIENT_ID);

        RESPONSE_STATUS.set(500);
        Result unavailable = call("/runtime/apis/payments/v1/accounts");
        assertThat(unavailable.body())
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain(CLIENT_ID);
    }

    @Test
    void theRawAuthorizationHeaderIsNeverForwardedDownstream() {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(
                "/runtime/apis/payments/v1/accounts");
        builder.header(HttpHeaders.AUTHORIZATION, basicHeader());
        builder.header("X-Custom", "custom-value");
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstream.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(downstream.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(downstream.get().getRequest().getHeaders().getFirst("X-Custom")).isEqualTo("custom-value");
    }

    @Test
    void validClientCredentialsInjectTrustedIdentityHeaders() {
        Result result = call("/runtime/apis/payments/v1/accounts", basicHeader());

        assertThat(result.forwarded()).isTrue();
        assertThat(result.downstream().getRequest().getHeaders())
                .containsEntry(TrustedIdentityHeaders.USER_ID, List.of("42"))
                .containsEntry(TrustedIdentityHeaders.APPLICATION_ID, List.of("7"))
                .containsEntry(TrustedIdentityHeaders.CLIENT_ID, List.of(CLIENT_ID))
                .doesNotContainKey(TrustedIdentityHeaders.ROLES);
    }

    @Test
    void rolesHeaderIsNeverAddedForClientCredentials() {
        Result result = call("/runtime/apis/payments/v1/accounts", basicHeader());

        assertThat(result.forwarded()).isTrue();
        assertThat(result.downstream().getRequest().getHeaders())
                .doesNotContainKey(TrustedIdentityHeaders.ROLES);
    }

    @Test
    void clientSuppliedIdentityHeadersCannotOverrideVerifiedValues() {
        Result result = call("/runtime/apis/payments/v1/accounts", basicHeader(), Map.of(
                TrustedIdentityHeaders.USER_ID, "attacker",
                TrustedIdentityHeaders.ROLES, "ADMIN",
                TrustedIdentityHeaders.APPLICATION_ID, "attacker-app",
                TrustedIdentityHeaders.CLIENT_ID, "attacker-client"));

        assertThat(result.forwarded()).isTrue();
        assertThat(result.downstream().getRequest().getHeaders())
                .containsEntry(TrustedIdentityHeaders.USER_ID, List.of("42"))
                .containsEntry(TrustedIdentityHeaders.APPLICATION_ID, List.of("7"))
                .containsEntry(TrustedIdentityHeaders.CLIENT_ID, List.of(CLIENT_ID))
                .doesNotContainKey(TrustedIdentityHeaders.ROLES);
    }

    @Test
    void clientSecretIsNeverForwardedInHeaders() {
        Result result = call("/runtime/apis/payments/v1/accounts", basicHeader());

        String encoded = Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        List<String> headerValues = result.downstream().getRequest().getHeaders().values().stream()
                .flatMap(List::stream)
                .toList();
        assertThat(result.downstream().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(headerValues)
                .noneMatch(value -> value.contains(CLIENT_SECRET))
                .noneMatch(value -> value.contains("Basic " + encoded));
    }

    @Test
    void nonRuntimeClientCredentialRequestDoesNotGetTrustedIdentityHeaders() {
        Result result = call("/accounts/1", basicHeader());

        assertThat(result.forwarded()).isTrue();
        assertThat(result.downstream().getRequest().getHeaders())
                .doesNotContainKeys(
                        TrustedIdentityHeaders.USER_ID,
                        TrustedIdentityHeaders.ROLES,
                        TrustedIdentityHeaders.APPLICATION_ID,
                        TrustedIdentityHeaders.CLIENT_ID);
    }

    @Test
    void unrelatedHeadersArePreservedForClientCredentialRequests() {
        Result result = call("/runtime/apis/payments/v1/accounts", basicHeader(),
                Map.of("X-Custom", "custom-value"));

        assertThat(result.forwarded()).isTrue();
        assertThat(result.downstream().getRequest().getHeaders().getFirst("X-Custom"))
                .isEqualTo("custom-value");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("failed to allocate a free port", e);
        }
    }
}