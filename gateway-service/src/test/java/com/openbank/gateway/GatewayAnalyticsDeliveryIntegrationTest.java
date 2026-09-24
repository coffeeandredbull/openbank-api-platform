package com.openbank.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.gateway.analytics.AnalyticsDeliveryProperties;
import com.openbank.gateway.analytics.InMemoryRuntimeAnalyticsEventSink;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
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
class GatewayAnalyticsDeliveryIntegrationTest {

    record CapturedRequest(String name, String method, String path, String query,
                           Map<String, List<String>> headers, String body) {
    }

    private static final String CLIENT_ID = "client-integration-0001";
    private static final String CLIENT_SECRET = "integration-super-secret";
    private static final String ANALYTICS_TOKEN = "gateway-test-analytics-token";

    enum AnalyticsMode { OK, ERROR_500, SLOW, CLOSE }

    private static final AtomicReference<AnalyticsMode> ANALYTICS_MODE =
            new AtomicReference<>(AnalyticsMode.OK);
    private static final AtomicReference<CapturedRequest> MANAGED = new AtomicReference<>();
    private static final List<CapturedRequest> ANALYTICS_REQUESTS =
            Collections.synchronizedList(new ArrayList<>());

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no");

    private static final HttpServer IDENTITY_SERVER = startServer("identity");
    private static final HttpServer API_MANAGEMENT_SERVER = startServer("api-management");
    private static final HttpServer PAYMENT_SERVER = startServer("payment");
    private static final HttpServer MANAGED_SERVER = startServer("managed");
    private static final HttpServer ANALYTICS_SERVER = startServer("analytics");

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("IDENTITY_SERVICE_URL", () -> "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort());
        registry.add("API_MANAGEMENT_SERVICE_URL", () -> "http://127.0.0.1:" + API_MANAGEMENT_SERVER.getAddress().getPort());
        registry.add("PAYMENT_SERVICE_URL", () -> "http://127.0.0.1:" + PAYMENT_SERVER.getAddress().getPort());
        registry.add("MANAGED_API_TARGET_URL", () -> "http://127.0.0.1:" + MANAGED_SERVER.getAddress().getPort());
        registry.add("ANALYTICS_SERVICE_URL", () -> "http://127.0.0.1:" + ANALYTICS_SERVER.getAddress().getPort());
        registry.add("ANALYTICS_INTERNAL_TOKEN", () -> ANALYTICS_TOKEN);
        registry.add("ANALYTICS_DELIVERY_TIMEOUT_MS", () -> "500");
        registry.add("ANALYTICS_QUEUE_CAPACITY", () -> "64");
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

        if ("analytics".equals(name)) {
            ANALYTICS_REQUESTS.add(new CapturedRequest(name, method, path,
                    exchange.getRequestURI().getRawQuery(), headers, body));
            switch (ANALYTICS_MODE.get()) {
                case OK -> respond(exchange, 202, "");
                case ERROR_500 -> respond(exchange, 500, "");
                case SLOW -> {
                    sleepUninterruptibly(10_000);
                    respond(exchange, 202, "");
                }
                case CLOSE -> exchange.close();
            }
            return;
        }

        if ("managed".equals(name)) {
            MANAGED.set(new CapturedRequest(name, method, path,
                    exchange.getRequestURI().getRawQuery(), headers, body));
            respond(exchange, 201, "{\"upstream\":\"managed\"}");
            return;
        }
        respond(exchange, 201, "{\"upstream\":\"" + name + "\"}");
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        ANALYTICS_SERVER.stop(0);
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    InMemoryRuntimeAnalyticsEventSink sink;

    @BeforeEach
    void reset() {
        ANALYTICS_MODE.set(AnalyticsMode.OK);
        ANALYTICS_REQUESTS.clear();
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

    private List<CapturedRequest> awaitAnalyticsRequests(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<CapturedRequest> received = List.copyOf(ANALYTICS_REQUESTS);
        while (received.size() < expected && System.nanoTime() < deadline) {
            sleepUninterruptibly(20);
            received = List.copyOf(ANALYTICS_REQUESTS);
        }
        return received;
    }

    private CapturedRequest singleAnalyticsDelivery(int expected) {
        List<CapturedRequest> requests = awaitAnalyticsRequests(expected);
        CapturedRequest delivery = requests.get(0);
        assertThat(delivery.name()).isEqualTo("analytics");
        assertThat(delivery.method()).isEqualTo("POST");
        assertThat(delivery.path()).isEqualTo("/internal/analytics/events");
        assertThat(delivery.query()).isNull();
        return delivery;
    }

    private void assertNoAnalyticsDeliveryOccurs() {
        sleepUninterruptibly(400);
        assertThat(ANALYTICS_REQUESTS).isEmpty();
    }

    private static List<String> requestedHeaderValues(CapturedRequest request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }

    private static void assertInternalRequestIsSanitized(CapturedRequest delivery) {
        assertThat(requestedHeaderValues(delivery, AnalyticsDeliveryProperties.INTERNAL_TOKEN_HEADER))
                .containsExactly(ANALYTICS_TOKEN);
        assertThat(requestedHeaderValues(delivery, HttpHeaders.AUTHORIZATION)).isEmpty();
        assertThat(requestedHeaderValues(delivery, "Proxy-Authorization")).isEmpty();
        assertThat(requestedHeaderValues(delivery, TrustedIdentityHeaders.USER_ID)).isEmpty();
        assertThat(requestedHeaderValues(delivery, TrustedIdentityHeaders.ROLES)).isEmpty();
        assertThat(requestedHeaderValues(delivery, TrustedIdentityHeaders.APPLICATION_ID)).isEmpty();
        assertThat(requestedHeaderValues(delivery, TrustedIdentityHeaders.CLIENT_ID)).isEmpty();
    }

    private static JsonNode body(CapturedRequest delivery) throws Exception {
        return OBJECT_MAPPER.readTree(delivery.body());
    }

    @Test
    void jwtEventIsDeliveredToAnalyticsEndpoint() throws Exception {
        jwtAccountsGet().expectStatus().isEqualTo(201);

        CapturedRequest delivery = singleAnalyticsDelivery(1);
        assertInternalRequestIsSanitized(delivery);
        assertThat(requestedHeaderValues(delivery, "Content-Type"))
                .anyMatch(v -> v.toLowerCase().contains("application/json"));

        JsonNode event = body(delivery);
        assertThat(event.get("timestamp").asText()).isNotBlank();
        assertThat(event.get("apiContext").asText()).isEqualTo("/payments");
        assertThat(event.get("apiVersion").asText()).isEqualTo("v1");
        assertThat(event.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(event.get("statusCode").asInt()).isEqualTo(201);
        assertThat(event.get("latencyMs").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(event.get("authenticationType").asText()).isEqualTo("JWT");
        assertThat(event.get("userId").asLong()).isEqualTo(1L);
        if (event.has("applicationId")) {
            assertThat(event.get("applicationId").isNull()).isTrue();
        }
    }

    @Test
    void clientCredentialIdentityIsDeliveredWithBothIds() throws Exception {
        clientCredentialAccountsGet().expectStatus().isEqualTo(201);

        CapturedRequest delivery = singleAnalyticsDelivery(1);
        assertInternalRequestIsSanitized(delivery);

        JsonNode event = body(delivery);
        assertThat(event.get("authenticationType").asText()).isEqualTo("CLIENT_CREDENTIAL");
        assertThat(event.get("userId").asLong()).isEqualTo(42L);
        assertThat(event.get("applicationId").asLong()).isEqualTo(7L);
    }

    @Test
    void analyticsUnavailableDoesNotAffectRuntimeRequest() {
        ANALYTICS_MODE.set(AnalyticsMode.CLOSE);

        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(MANAGED.get()).isNotNull();
    }

    @Test
    void slowAnalyticsDoesNotDelayRuntimeRequest() {
        // The analytics stub sleeps 10s before responding — far longer than the
        // WebTestClient read timeout (5s). A 201 response therefore proves the
        // runtime request never waited on analytics delivery; had the request
        // path been coupled to it, this exchange would time out.
        ANALYTICS_MODE.set(AnalyticsMode.SLOW);

        long started = System.nanoTime();
        jwtAccountsGet().expectStatus().isEqualTo(201);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(MANAGED.get()).isNotNull();
        assertThat(elapsedMs).isLessThan(5000);
        awaitAnalyticsRequests(1);
    }

    @Test
    void analyticsHttpErrorDoesNotAffectRuntimeRequest() {
        ANALYTICS_MODE.set(AnalyticsMode.ERROR_500);

        jwtAccountsGet().expectStatus().isEqualTo(201);
        assertThat(MANAGED.get()).isNotNull();
        assertThat(awaitAnalyticsRequests(1)).hasSize(1);
    }

    @Test
    void managementRouteDoesNotProduceAnalyticsDelivery() {
        webTestClient.get()
                .uri("/apis/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201);

        assertNoAnalyticsDeliveryOccurs();
    }

    @Test
    void multipleRuntimeRequestsProduceMultipleDeliveries() throws Exception {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        clientCredentialAccountsGet().expectStatus().isEqualTo(201);

        List<CapturedRequest> deliveries = awaitAnalyticsRequests(2);
        assertThat(deliveries).hasSize(2);

        JsonNode first = body(deliveries.get(0));
        JsonNode second = body(deliveries.get(1));
        assertThat(first.get("timestamp").asText()).isNotEqualTo(second.get("timestamp").asText());
    }
}