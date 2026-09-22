package com.openbank.gateway;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.openbank.gateway.filter.SubscriptionEnforcementFilter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewaySubscriptionEnforcementIntegrationTest {

    record CapturedRequest(String name, String method, String path, String query,
                           Map<String, List<String>> headers, String body) {
    }

    private static final Set<String> SUBSCRIPTIONS = new HashSet<>();
    private static final AtomicBoolean CHECK_UNAVAILABLE = new AtomicBoolean(false);
    private static final AtomicReference<Map<String, String>> CHECK_QUERY = new AtomicReference<>();

    private static final AtomicReference<CapturedRequest> MANAGED = new AtomicReference<>();

    private static final HttpServer IDENTITY_SERVER = startServer("identity");
    private static final HttpServer API_MANAGEMENT_SERVER = startServer("api-management");
    private static final HttpServer PAYMENT_SERVER = startServer("payment");
    private static final HttpServer MANAGED_SERVER = startServer("managed");

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("IDENTITY_SERVICE_URL", () -> "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort());
        registry.add("API_MANAGEMENT_SERVICE_URL", () -> "http://127.0.0.1:" + API_MANAGEMENT_SERVER.getAddress().getPort());
        registry.add("PAYMENT_SERVICE_URL", () -> "http://127.0.0.1:" + PAYMENT_SERVER.getAddress().getPort());
        registry.add("MANAGED_API_TARGET_URL", () -> "http://127.0.0.1:" + MANAGED_SERVER.getAddress().getPort());
        registry.add("JWT_SECRET", () -> GatewayTestJwt.SECRET);
    }

    private static HttpServer startServer(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handle(name, exchange));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start test upstream " + name, e);
        }
    }

    private static void handle(String name, HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();

        if ("api-management".equals(name) && "/internal/subscription-check".equals(path)) {
            Map<String, String> params = parseQuery(query);
            CHECK_QUERY.set(params);
            int status;
            String responseBody;
            if (CHECK_UNAVAILABLE.get()) {
                status = 503;
                responseBody = "{\"status\":503}";
            } else {
                status = 200;
                boolean subscribed = SUBSCRIPTIONS.contains(
                        params.getOrDefault("contextPath", "") + "|" + params.getOrDefault("version", ""));
                responseBody = "{\"subscribed\":" + subscribed + "}";
            }
            respond(exchange, status, responseBody);
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, List<String>> headers = exchange.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if ("managed".equals(name)) {
            MANAGED.set(new CapturedRequest(name, method, path, query, headers, body));
        }

        int status;
        String responseBody;
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (lastSegment.matches("\\d+") && lastSegment.length() == 3) {
            status = Integer.parseInt(lastSegment);
            responseBody = "{\"status\":" + status + ",\"code\":\"BACKEND_" + status
                    + "\",\"message\":\"backend says " + status + "\"}";
        } else {
            status = 201;
            responseBody = "{\"upstream\":\"" + name + "\",\"method\":\"" + method + "\",\"path\":\"" + path + "\"}";
        }
        respond(exchange, status, responseBody);
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

    private static void respond(HttpExchange exchange, int status, String responseBody) throws IOException {
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }

    @AfterAll
    static void stopServers() {
        IDENTITY_SERVER.stop(0);
        API_MANAGEMENT_SERVER.stop(0);
        PAYMENT_SERVER.stop(0);
        MANAGED_SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        SUBSCRIPTIONS.clear();
        CHECK_UNAVAILABLE.set(false);
        CHECK_QUERY.set(null);
        MANAGED.set(null);
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    void subscribedRuntimeRequestIsForwardedToTheManagedBackend() {
        SUBSCRIPTIONS.add("/payments|v1");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("managed");

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(captured.method()).isEqualTo("GET");
        assertThat(captured.path()).isEqualTo("/runtime/apis/payments/v1/accounts");
    }

    @Test
    void unsubscribedRuntimeRequestReturns403AndIsNotForwarded() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.error").isEqualTo("Forbidden")
                .jsonPath("$.path").isEqualTo("/runtime/apis/payments/v1/accounts")
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_REQUIRED")
                .jsonPath("$.message").isEqualTo("An active subscription is required to access this API");

        assertThat(MANAGED.get()).isNull();
    }

    @Test
    void runtimeRequestForAnUnsubscribedVersionReturns403() {
        SUBSCRIPTIONS.add("/payments|v1");

        webTestClient.get()
                .uri("/runtime/apis/payments/v2/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_REQUIRED");

        assertThat(MANAGED.get()).isNull();
    }

    @Test
    void adminHasNoSubscriptionBypass() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_REQUIRED");
    }

    @Test
    void runtimeRequestWithoutJwtReturns401() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED");

        assertThat(MANAGED.get()).isNull();
    }

    @Test
    void expiredTokenReturns401() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.expiredAdmin())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void unavailableCheckServiceReturns503AndIsNotForwarded() {
        CHECK_UNAVAILABLE.set(true);

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Service Unavailable")
                .jsonPath("$.path").isEqualTo("/runtime/apis/payments/v1/accounts")
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Subscription verification is temporarily unavailable");

        assertThat(MANAGED.get()).isNull();
    }

    @Test
    void subscribedRequestPreservesMethodQueryAndBody() {
        SUBSCRIPTIONS.add("/payments|v1");

        webTestClient.post()
                .uri("/runtime/apis/payments/v1/transfers?trace=xyz")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .bodyValue("{\"amount\":123}")
                .exchange()
                .expectStatus().isEqualTo(201);

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.path()).isEqualTo("/runtime/apis/payments/v1/transfers");
        assertThat(captured.query()).isEqualTo("trace=xyz");
        assertThat(captured.body()).isEqualTo("{\"amount\":123}");
    }

    @Test
    void authorizationHeaderIsPreservedToTheManagedBackend() {
        SUBSCRIPTIONS.add("/payments|v1");
        String token = GatewayTestJwt.admin();

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(201);

        List<List<String>> forwarded = MANAGED.get().headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(HttpHeaders.AUTHORIZATION))
                .map(Map.Entry::getValue)
                .toList();
        assertThat(forwarded).isNotEmpty();
        assertThat(forwarded.get(0)).contains("Bearer " + token);
    }

    @Test
    void backendErrorsPassThroughUnchangedWhenSubscribed() {
        SUBSCRIPTIONS.add("/payments|v1");
        String token = "Bearer " + GatewayTestJwt.admin();

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/404")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.code").isEqualTo("BACKEND_404");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/409")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("BACKEND_409");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/500")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.code").isEqualTo("BACKEND_500");
    }

    @Test
    void clientSuppliedUserIdCannotBypassTheSubscriptionCheck() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts?userId=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.developer())
                .header("X-User-Id", "1")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_REQUIRED");

        Map<String, String> checkQuery = CHECK_QUERY.get();
        assertThat(checkQuery).isNotNull();
        assertThat(checkQuery).doesNotContainKey("userId");
        assertThat(checkQuery).doesNotContainKey("X-User-Id");
        assertThat(MANAGED.get()).isNull();
    }

    @Test
    void platformRoutesAreNotSubjectToSubscriptionChecks() {
        webTestClient.get()
                .uri("/apis/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("api-management");

        webTestClient.get()
                .uri("/accounts/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("payment");

        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        assertThat(CHECK_QUERY.get()).isNull();
    }

    @Test
    void rejectionResponsesDoNotExposeJwtOrUpstreamDetails() {
        String token = GatewayTestJwt.admin();
        byte[] body = webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .returnResult()
                .getResponseBody();
        String text = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(body)).toString();
        assertThat(text)
                .doesNotContain(token)
                .doesNotContain("127.0.0.1")
                .doesNotContain("localhost")
                .doesNotContain("http://")
                .doesNotContain("com.openbank");
    }

    @Test
    void authorizationValuesAreNeverLoggedByTheSubscriptionFilter() {
        ch.qos.logback.classic.Logger filterLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SubscriptionEnforcementFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        String validToken = GatewayTestJwt.admin();
        try {
            filterLogger.addAppender(appender);
            SUBSCRIPTIONS.add("/payments|v1");
            webTestClient.get()
                    .uri("/runtime/apis/payments/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                    .exchange()
                    .expectStatus().isEqualTo(201);
            SUBSCRIPTIONS.clear();
            webTestClient.get()
                    .uri("/runtime/apis/payments/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                    .exchange()
                    .expectStatus().isForbidden();
        } finally {
            filterLogger.detachAppender(appender);
        }
        assertThat(appender.list)
                .noneMatch(event -> event.getFormattedMessage().contains("Bearer"))
                .noneMatch(event -> event.getFormattedMessage().contains(validToken));
    }
}