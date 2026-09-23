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
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=" + GatewayTestJwt.SECRET,
                "RATE_LIMIT_REQUESTS=100",
                "RATE_LIMIT_WINDOW_SECONDS=60"
        })
@AutoConfigureWebTestClient
class GatewayManagedUpstreamUnavailableIntegrationTest {

    private static final Set<String> SUBSCRIPTIONS = new HashSet<>();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no");

    private static final HttpServer API_MANAGEMENT_SERVER = startServer();

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("API_MANAGEMENT_SERVICE_URL",
                () -> "http://127.0.0.1:" + API_MANAGEMENT_SERVER.getAddress().getPort());
        registry.add("MANAGED_API_TARGET_URL",
                () -> "http://127.0.0.1:" + unusedPort());
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handle(exchange));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start subscription-check upstream", e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        if ("/internal/subscription-check".equals(exchange.getRequestURI().getPath())) {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            boolean subscribed = SUBSCRIPTIONS.contains(
                    params.getOrDefault("contextPath", "") + "|" + params.getOrDefault("version", ""));
            respond(exchange, 200, "{\"subscribed\":" + subscribed + "}");
            return;
        }
        respond(exchange, 201, "{\"upstream\":\"api-management\"}");
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

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("failed to allocate a free port", e);
        }
    }

    @AfterAll
    static void stopServer() {
        API_MANAGEMENT_SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        SUBSCRIPTIONS.clear();
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    void unavailableManagedUpstreamReturns503UpstreamServiceUnavailable() {
        SUBSCRIPTIONS.add("/payments|v1");

        byte[] body = webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Service Unavailable")
                .jsonPath("$.code").isEqualTo("UPSTREAM_SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("The requested service is currently unavailable")
                .returnResult()
                .getResponseBody();

        String text = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(body)).toString();
        assertThat(text)
                .doesNotContain("127.0.0.1")
                .doesNotContain("localhost")
                .doesNotContain("http://")
                .doesNotContain("ConnectException")
                .doesNotContain("java.")
                .doesNotContain("stack");
    }
}