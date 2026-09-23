package com.openbank.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=" + GatewayTestJwt.SECRET,
                "RATE_LIMIT_REQUESTS=1",
                "RATE_LIMIT_WINDOW_SECONDS=1",
                "spring.data.redis.password=" + GatewayRateLimitingWindowRolloverIntegrationTest.TEST_REDIS_PASSWORD
        })
@AutoConfigureWebTestClient
class GatewayRateLimitingWindowRolloverIntegrationTest {

    static final String TEST_REDIS_PASSWORD = "integration-test-redis-password";

    private static final Set<String> SUBSCRIPTIONS = new HashSet<>();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no",
                    "--requirepass", TEST_REDIS_PASSWORD);

    private static final HttpServer API_MANAGEMENT_SERVER = startServer("api-management");
    private static final HttpServer MANAGED_SERVER = startServer("managed");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("API_MANAGEMENT_SERVICE_URL",
                () -> "http://127.0.0.1:" + API_MANAGEMENT_SERVER.getAddress().getPort());
        registry.add("MANAGED_API_TARGET_URL",
                () -> "http://127.0.0.1:" + MANAGED_SERVER.getAddress().getPort());
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
        if ("api-management".equals(name) && "/internal/subscription-check".equals(path)) {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            boolean subscribed = SUBSCRIPTIONS.contains(
                    params.getOrDefault("contextPath", "") + "|" + params.getOrDefault("version", ""));
            respond(exchange, 200, "{\"subscribed\":" + subscribed + "}");
            return;
        }
        if ("managed".equals(name)) {
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
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void expiredWindowAllowsRequestsAgain() throws InterruptedException {
        SUBSCRIPTIONS.add("/roll|v1");
        String token = GatewayTestJwt.admin();

        webTestClient.get()
                .uri("/runtime/apis/roll/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(201);

        webTestClient.get()
                .uri("/runtime/apis/roll/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMIT_EXCEEDED");

        long ttlBeforeWait = redisTemplate.getExpire("rate_limit:1:/roll:v1",
                java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttlBeforeWait).isBetween(1L, 1000L);

        Thread.sleep(1200);

        webTestClient.get()
                .uri("/runtime/apis/roll/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(201);
    }

    @Test
    void initialRequestEstablishesTheWindowTtl() {
        SUBSCRIPTIONS.add("/rollttl|v1");

        webTestClient.get()
                .uri("/runtime/apis/rollttl/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.developer())
                .exchange()
                .expectStatus().isEqualTo(201);

        Long ttl = redisTemplate.getExpire("rate_limit:2:/rollttl:v1",
                java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, 1000L);
    }
}