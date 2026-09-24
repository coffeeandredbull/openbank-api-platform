package com.openbank.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayClientCredentialIntegrationTest {

    record CapturedRequest(String name, String method, String path, String query,
                           Map<String, List<String>> headers, String body) {
    }

    private static final String CLIENT_ID = "client-integration-0001";
    private static final String CLIENT_SECRET = "integration-super-secret";

    private static final String AUTHENTICATED_BODY = "{\"authenticated\":true,\"applicationId\":7,\"ownerUserId\":42,"
            + "\"subscribed\":true,\"tierId\":1,\"tierName\":\"Gold\","
            + "\"requestsPerWindow\":3,\"windowSeconds\":60}";

    private static final AtomicReference<String> CREDENTIAL_BODY = new AtomicReference<>(
            AUTHENTICATED_BODY);
    private static final AtomicInteger CREDENTIAL_STATUS = new AtomicInteger(200);
    private static final AtomicBoolean CHECK_UNAVAILABLE = new AtomicBoolean(false);
    private static final AtomicReference<Map<String, String>> CHECK_QUERY = new AtomicReference<>();
    private static final AtomicReference<String> CHECK_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicInteger MANAGED_HITS = new AtomicInteger(0);

    private static final AtomicReference<CapturedRequest> MANAGED = new AtomicReference<>();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no");

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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
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

        if ("api-management".equals(name) && "/internal/credential-check".equals(path)) {
            CHECK_QUERY.set(parseQuery(query));
            CHECK_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            if (CHECK_UNAVAILABLE.get()) {
                respond(exchange, 503, "{\"status\":503}");
            } else {
                respond(exchange, CREDENTIAL_STATUS.get(), CREDENTIAL_BODY.get());
            }
            return;
        }
        if ("api-management".equals(name) && "/internal/subscription-check".equals(path)) {
            respond(exchange, 200, "{\"subscribed\":true,"
                    + "\"tierId\":1,\"tierName\":\"Gold\","
                    + "\"requestsPerWindow\":3,\"windowSeconds\":60}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, List<String>> headers = exchange.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if ("managed".equals(name)) {
            MANAGED.set(new CapturedRequest(name, method, path, query, headers, body));
            MANAGED_HITS.incrementAndGet();
            respond(exchange, 201, "{\"upstream\":\"managed\",\"method\":\"" + method + "\",\"path\":\"" + path + "\"}");
            return;
        }

        respond(exchange, 201, "{\"upstream\":\"" + name + "\"}");
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
        CREDENTIAL_BODY.set(AUTHENTICATED_BODY);
        CREDENTIAL_STATUS.set(200);
        CHECK_UNAVAILABLE.set(false);
        CHECK_QUERY.set(null);
        CHECK_AUTHORIZATION.set(null);
        MANAGED.set(null);
        MANAGED_HITS.set(0);
    }

    @Autowired
    WebTestClient webTestClient;

    private String basicHeader() {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validClientCredentialsReachTheManagedBackendWithAuthorizationStripped() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("managed");

        assertThat(CHECK_QUERY.get()).containsEntry("contextPath", "/payments");
        assertThat(CHECK_QUERY.get()).containsEntry("version", "v1");
        assertThat(CHECK_AUTHORIZATION.get()).isEqualTo(basicHeader());

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(captured.path()).isEqualTo("/runtime/apis/payments/v1/accounts");
        assertThat(captured.headers()).doesNotContainKey("Authorization");
    }

    @Test
    void unsubscribedApplicationReturns403AndIsNotForwarded() {
        CREDENTIAL_BODY.set(
                "{\"authenticated\":true,\"applicationId\":7,\"ownerUserId\":42,\"subscribed\":false}");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.path").isEqualTo("/runtime/apis/payments/v1/accounts")
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_REQUIRED")
                .jsonPath("$.message").isEqualTo("An active subscription is required to access this API");

        assertThat(MANAGED.get()).isNull();
    }

    @Test
    void invalidClientCredentialsReturn401AndAreNotForwarded() {
        CREDENTIAL_STATUS.set(401);
        CREDENTIAL_BODY.set("{\"authenticated\":false}");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized")
                .jsonPath("$.path").isEqualTo("/runtime/apis/payments/v1/accounts")
                .jsonPath("$.code").isEqualTo("CLIENT_CREDENTIAL_INVALID")
                .jsonPath("$.message").isEqualTo("Invalid client credentials");

        assertThat(MANAGED.get()).isNull();
    }

    @Test
    void unavailableCredentialCheckReturns503AndIsNotForwarded() {
        CHECK_UNAVAILABLE.set(true);

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Service Unavailable")
                .jsonPath("$.path").isEqualTo("/runtime/apis/payments/v1/accounts")
                .jsonPath("$.code").isEqualTo("CREDENTIAL_SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Credential verification is temporarily unavailable");

        assertThat(MANAGED.get()).isNull();
    }

    @Test
    void applicationClientCredentialsAreRateLimitedIndependently() {
        for (int i = 0; i < 3; i++) {
            webTestClient.get()
                    .uri("/runtime/apis/rlapp/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, basicHeader())
                    .exchange()
                    .expectStatus().isEqualTo(201);
        }

        webTestClient.get()
                .uri("/runtime/apis/rlapp/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.code").isEqualTo("RATE_LIMIT_EXCEEDED")
                .jsonPath("$.message").isEqualTo("Rate limit exceeded");

        assertThat(MANAGED_HITS.get()).isEqualTo(3);
    }

    @Test
    void malformedRuntimePathWithClientCredentialsReturns403() {
        webTestClient.get()
                .uri("/runtime/apis/payments")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_REQUIRED");

        assertThat(CHECK_QUERY.get()).isNull();
        assertThat(MANAGED.get()).isNull();
    }

    @Test
    void bearerAuthenticationStillWorksUnchanged() {
        String token = GatewayTestJwt.admin();
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("managed");

        List<List<String>> forwarded = MANAGED.get().headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(HttpHeaders.AUTHORIZATION))
                .map(Map.Entry::getValue)
                .toList();
        assertThat(forwarded).isNotEmpty();
        assertThat(forwarded.get(0)).contains("Bearer " + token);
    }

    @Test
    void managementRoutesRejectBasicCredentialsWith401() {
        webTestClient.get()
                .uri("/apis")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void rejectionResponsesNeverExposeTheClientSecret() {
        CREDENTIAL_STATUS.set(401);
        CREDENTIAL_BODY.set("{\"authenticated\":false}");
        assertLeakFreeSecret(webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isUnauthorized());

        CREDENTIAL_STATUS.set(200);
        CREDENTIAL_BODY.set(
                "{\"authenticated\":true,\"applicationId\":7,\"ownerUserId\":42,\"subscribed\":false}");
        assertLeakFreeSecret(webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isForbidden());

        CHECK_UNAVAILABLE.set(true);
        assertLeakFreeSecret(webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isEqualTo(503));
    }

    private void assertLeakFreeSecret(WebTestClient.ResponseSpec spec) {
        byte[] body = spec.expectBody().returnResult().getResponseBody();
        String text = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(body)).toString();
        assertThat(text)
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain(CLIENT_ID)
                .doesNotContain("127.0.0.1")
                .doesNotContain("localhost")
                .doesNotContain("com.openbank");
    }
}