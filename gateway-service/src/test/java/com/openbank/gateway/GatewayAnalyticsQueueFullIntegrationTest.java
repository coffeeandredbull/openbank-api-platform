package com.openbank.gateway;

import com.openbank.gateway.analytics.InMemoryRuntimeAnalyticsEventSink;
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
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the bounded-queue contract: ANALYTICS_QUEUE_CAPACITY=1 with delivery
 * disabled (no ANALYTICS_INTERNAL_TOKEN). Two consecutive runtime requests both
 * succeed (201) and neither blocks, yet only one event is retained — the second
 * is silently dropped when the buffer is full.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayAnalyticsQueueFullIntegrationTest {

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
        registry.add("ANALYTICS_QUEUE_CAPACITY", () -> "1");
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
        sink.clear();
    }

    private WebTestClient.ResponseSpec jwtAccountsGet() {
        return webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange();
    }

    @Test
    void fullQueueStillAllowsBothRuntimeRequestsToSucceed() {
        jwtAccountsGet().expectStatus().isEqualTo(201);
        jwtAccountsGet().expectStatus().isEqualTo(201);
    }

    @Test
    void overflowIsSilentlyDroppedWithoutBlocking() {
        long started = System.nanoTime();
        jwtAccountsGet().expectStatus().isEqualTo(201);
        jwtAccountsGet().expectStatus().isEqualTo(201);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).isLessThan(8000);
        assertThat(sink.snapshot()).hasSize(1);
    }
}