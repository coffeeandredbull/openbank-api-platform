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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=" + GatewayTestJwt.SECRET,
                "RATE_LIMIT_REQUESTS=3",
                "RATE_LIMIT_WINDOW_SECONDS=60",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6399",
                "spring.data.redis.password="
        })
@AutoConfigureWebTestClient
class GatewayRateLimitingUnavailableIntegrationTest {

    private static final Set<String> SUBSCRIPTIONS = new HashSet<>();
    private static final AtomicInteger MANAGED_HITS = new AtomicInteger(0);

    private static final HttpServer API_MANAGEMENT_SERVER = startServer("api-management");
    private static final HttpServer MANAGED_SERVER = startServer("managed");

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("API_MANAGEMENT_SERVICE_URL",
                () -> "http://127.0.0.1:" + API_MANAGEMENT_SERVER.getAddress().getPort());
        registry.add("MANAGED_API_TARGET_URL",
                () -> "http://127.0.0.1:" + MANAGED_SERVER.getAddress().getPort());
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
        if ("api-management".equals(name) && "/internal/subscription-check".equals(path)) {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            boolean subscribed = SUBSCRIPTIONS.contains(
                    params.getOrDefault("contextPath", "") + "|" + params.getOrDefault("version", ""));
            respond(exchange, 200, "{\"subscribed\":" + subscribed + "}");
            return;
        }
        if ("managed".equals(name)) {
            MANAGED_HITS.incrementAndGet();
            respond(exchange, 201, "{\"upstream\":\"managed\"}");
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
        API_MANAGEMENT_SERVER.stop(0);
        MANAGED_SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        SUBSCRIPTIONS.clear();
        MANAGED_HITS.set(0);
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    void unreachableRedisReturns503AndTheRequestIsNotForwarded() {
        SUBSCRIPTIONS.add("/down|v1");
        String token = GatewayTestJwt.admin();

        byte[] body = webTestClient.get()
                .uri("/runtime/apis/down/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Service Unavailable")
                .jsonPath("$.path").isEqualTo("/runtime/apis/down/v1/accounts")
                .jsonPath("$.code").isEqualTo("RATE_LIMIT_SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Rate limiting service unavailable")
                .returnResult()
                .getResponseBody();

        assertThat(StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(body)).toString())
                .doesNotContain(token)
                .doesNotContain("redis")
                .doesNotContain("localhost")
                .doesNotContain("127.0.0.1")
                .doesNotContain("com.openbank")
                .doesNotContain("lettuce")
                .doesNotContain("stack");
        assertThat(MANAGED_HITS.get()).isZero();
    }

    @Test
    void managementRoutesStillWorkWhenRedisIsUnavailable() {
        webTestClient.get()
                .uri("/apis/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201);

        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }
}