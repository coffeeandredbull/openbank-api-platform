package com.openbank.gateway;

import com.openbank.gateway.analytics.AuthenticationType;
import com.openbank.gateway.analytics.InMemoryRuntimeAnalyticsEventSink;
import com.openbank.gateway.analytics.RuntimeAnalyticsEvent;
import com.openbank.gateway.auth.TrustedIdentityHeaders;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayAnalyticsCaptureIntegrationTest {

    record CapturedRequest(String name, String method, String path, String query,
                           Map<String, List<String>> headers, String body) {
    }

    private static final String CLIENT_ID = "client-integration-0001";
    private static final String CLIENT_SECRET = "integration-super-secret";

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

        if ("api-management".equals(name) && "/internal/credential-check".equals(path)) {
            respond(exchange, 200,
                    "{\"authenticated\":true,\"applicationId\":7,\"ownerUserId\":42,\"subscribed\":true,"
                            + "\"tierId\":1,\"tierName\":\"Gold\","
                            + "\"requestsPerWindow\":1000,\"windowSeconds\":3600}");
            return;
        }
        if ("api-management".equals(name) && "/internal/subscription-check".equals(path)) {
            respond(exchange, 200, "{\"subscribed\":true,"
                    + "\"tierId\":1,\"tierName\":\"Gold\","
                    + "\"requestsPerWindow\":1000,\"windowSeconds\":3600}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, List<String>> headers = exchange.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if ("managed".equals(name)) {
            MANAGED.set(new CapturedRequest(name, method, path,
                    exchange.getRequestURI().getRawQuery(), headers, body));
            respond(exchange, 201, "{\"upstream\":\"managed\"}");
            return;
        }
        respond(exchange, 201, "{\"upstream\":\"" + name + "\"}");
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

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    InMemoryRuntimeAnalyticsEventSink sink;

    @BeforeEach
    void reset() {
        MANAGED.set(null);
        sink.clear();
    }

    private String basicHeader() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
    }

    private WebTestClient.ResponseSpec jwtAccountsGet() {
        return webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange();
    }

    private WebTestClient.ResponseSpec clientCredentialAccountsGet() {
        return webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange();
    }

    private List<RuntimeAnalyticsEvent> awaitEvents(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<RuntimeAnalyticsEvent> snapshot = sink.snapshot();
        while (snapshot.size() < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting analytics events", e);
            }
            snapshot = sink.snapshot();
        }
        return snapshot;
    }

    private RuntimeAnalyticsEvent singleEvent(int expected) {
        List<RuntimeAnalyticsEvent> events = awaitEvents(expected);
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private void assertNoEventsRecorded() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while settling for no analytics events", e);
        }
        assertThat(sink.snapshot()).isEmpty();
    }

    private static void assertJwtAuth(RuntimeAnalyticsEvent event, long userId) {
        assertThat(event.authenticationType()).isEqualTo(AuthenticationType.JWT);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.applicationId()).isNull();
    }

    private static void assertClientCredentialAuth(RuntimeAnalyticsEvent event, long userId, long applicationId) {
        assertThat(event.authenticationType()).isEqualTo(AuthenticationType.CLIENT_CREDENTIAL);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.applicationId()).isEqualTo(applicationId);
    }

    @Test
    void jwtRuntimeRequestCreatesExactlyOneAnalyticsEvent() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(awaitEvents(1)).hasSize(1);
    }

    @Test
    void jwtEventContainsCorrectUserId() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertJwtAuth(singleEvent(1), 1L);
    }

    @Test
    void jwtEventContainsNullApplicationId() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(singleEvent(1).applicationId()).isNull();
    }

    @Test
    void jwtEventContainsJwtAuthenticationType() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(singleEvent(1).authenticationType()).isEqualTo(AuthenticationType.JWT);
    }

    @Test
    void clientCredentialRuntimeRequestCreatesExactlyOneAnalyticsEvent() {
        clientCredentialAccountsGet().expectStatus().isEqualTo(201);
        assertThat(awaitEvents(1)).hasSize(1);
    }

    @Test
    void clientCredentialEventContainsCorrectUserId() {
        clientCredentialAccountsGet().expectStatus().isEqualTo(201);
        assertClientCredentialAuth(singleEvent(1), 42L, 7L);
    }

    @Test
    void clientCredentialEventContainsCorrectApplicationId() {
        clientCredentialAccountsGet().expectStatus().isEqualTo(201);
        assertThat(singleEvent(1).applicationId()).isEqualTo(7L);
    }

    @Test
    void clientCredentialEventContainsClientCredentialAuthenticationType() {
        clientCredentialAccountsGet().expectStatus().isEqualTo(201);
        assertThat(singleEvent(1).authenticationType())
                .isEqualTo(AuthenticationType.CLIENT_CREDENTIAL);
    }

    @Test
    void eventContainsCorrectHttpMethod() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(singleEvent(1).httpMethod()).isEqualTo(HttpMethod.GET);
    }

    @Test
    void eventContainsCorrectApiContext() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(singleEvent(1).apiContext()).isEqualTo("/payments");
    }

    @Test
    void eventContainsCorrectApiVersion() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(singleEvent(1).apiVersion()).isEqualTo("v1");
    }

    @Test
    void eventContainsActualUpstreamResponseStatus() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(singleEvent(1).statusCode()).isEqualTo(201);
    }

    @Test
    void eventLatencyIsNonNegative() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(singleEvent(1).latencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void eventTimestampIsPopulated() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(singleEvent(1).timestamp()).isNotNull();
    }

    @Test
    void managementRouteDoesNotCreateAnalyticsEvent() {
        webTestClient.get()
                .uri("/apis/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201);

        assertNoEventsRecorded();
    }

    @Test
    void invalidAuthenticationDoesNotCreateAnalyticsEvent() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + GatewayTestJwt.tampered(GatewayTestJwt.admin()))
                .exchange()
                .expectStatus().isUnauthorized();

        assertNoEventsRecorded();
    }

    @Test
    void analyticsCaptureDoesNotAlterUpstreamResponse() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(MANAGED.get()).isNotNull();
    }

    @Test
    void analyticsCaptureDoesNotAlterResponseBody() {
        jwtAccountsGet()
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("managed");
    }

    @Test
    void analyticsCaptureDoesNotAlterTrustedIdentityHeaders() {
        jwtAccountsGet().expectStatus().isEqualTo(201);

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        List<String> userId = captured.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(TrustedIdentityHeaders.USER_ID))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
        assertThat(userId).containsExactly("1");
    }

    @Test
    void multipleRuntimeRequestsProduceSeparateEvents() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        jwtAccountsGet().expectStatus().isEqualTo(201);

        List<RuntimeAnalyticsEvent> events = awaitEvents(2);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isNotSameAs(events.get(1));
        assertThat(events).extracting(RuntimeAnalyticsEvent::timestamp).doesNotHaveDuplicates();
    }
}