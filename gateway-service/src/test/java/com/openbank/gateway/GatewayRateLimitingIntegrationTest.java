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
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=" + GatewayTestJwt.SECRET,
                "RATE_LIMIT_REQUESTS=3",
                "RATE_LIMIT_WINDOW_SECONDS=60",
                "spring.data.redis.password=" + GatewayRateLimitingIntegrationTest.TEST_REDIS_PASSWORD
        })
@AutoConfigureWebTestClient
class GatewayRateLimitingIntegrationTest {

    static final String TEST_REDIS_PASSWORD = "integration-test-redis-password";

    private static final Set<String> SUBSCRIPTIONS = new HashSet<>();
    private static final AtomicBoolean CHECK_UNAVAILABLE = new AtomicBoolean(false);
    private static final AtomicInteger MANAGED_HITS = new AtomicInteger(0);
    private static final AtomicReference<String> MANAGED_BODY = new AtomicReference<>();
    private static final AtomicReference<Map<String, List<String>>> MANAGED_HEADERS = new AtomicReference<>();

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
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();

        if ("api-management".equals(name) && "/internal/subscription-check".equals(path)) {
            Map<String, String> params = parseQuery(query);
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

        if ("managed".equals(name)) {
            MANAGED_HITS.incrementAndGet();
            MANAGED_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            MANAGED_HEADERS.set(exchange.getRequestHeaders().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
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
        API_MANAGEMENT_SERVER.stop(0);
        MANAGED_SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        SUBSCRIPTIONS.clear();
        CHECK_UNAVAILABLE.set(false);
        MANAGED_HITS.set(0);
        MANAGED_BODY.set(null);
        MANAGED_HEADERS.set(null);
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    StringRedisTemplate redisTemplate;

    private void subscribe(String context, String version) {
        SUBSCRIPTIONS.add("/" + context + "|" + version);
    }

    @Test
    void requestsWithinTheLimitAreForwardedToTheManagedBackend() {
        subscribe("within", "v1");

        for (int i = 0; i < 3; i++) {
            webTestClient.get()
                    .uri("/runtime/apis/within/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                    .exchange()
                    .expectStatus().isEqualTo(201)
                    .expectBody()
                    .jsonPath("$.upstream").isEqualTo("managed");
        }
        assertThat(MANAGED_HITS.get()).isEqualTo(3);
    }

    @Test
    void requestBeyondTheLimitReturns429WithRetryAfterAndIsNotForwarded() {
        subscribe("beyond", "v1");
        String token = GatewayTestJwt.admin();

        for (int i = 0; i < 3; i++) {
            webTestClient.get()
                    .uri("/runtime/apis/beyond/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isEqualTo(201);
        }

        byte[] body = webTestClient.get()
                .uri("/runtime/apis/beyond/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectHeader().value(HttpHeaders.RETRY_AFTER,
                        value -> assertThat(Integer.parseInt(value)).isBetween(1, 60))
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.error").isEqualTo("Too Many Requests")
                .jsonPath("$.path").isEqualTo("/runtime/apis/beyond/v1/accounts")
                .jsonPath("$.code").isEqualTo("RATE_LIMIT_EXCEEDED")
                .jsonPath("$.message").isEqualTo("Rate limit exceeded")
                .jsonPath("$.fieldErrors").isEqualTo(Map.of())
                .returnResult()
                .getResponseBody();

        assertThat(StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(body)).toString())
                .doesNotContain(token)
                .doesNotContain("redis")
                .doesNotContain("localhost")
                .doesNotContain("127.0.0.1")
                .doesNotContain("com.openbank");
        assertThat(MANAGED_HITS.get()).isEqualTo(3);
    }

    @Test
    void allHttpMethodsShareTheSameCounter() {
        subscribe("methods", "v1");
        String token = GatewayTestJwt.admin();

        Runnable[] methods = {
                () -> webTestClient.get().uri("/runtime/apis/methods/v1/a")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange().expectStatus().isEqualTo(201),
                () -> webTestClient.post().uri("/runtime/apis/methods/v1/b")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isEqualTo(201),
                () -> webTestClient.put().uri("/runtime/apis/methods/v1/c")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isEqualTo(201)
        };
        for (Runnable method : methods) {
            method.run();
        }

        webTestClient.patch().uri("/runtime/apis/methods/v1/d")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(MANAGED_HITS.get()).isEqualTo(3);
    }

    @Test
    void differentUsersHaveIndependentCountersAndAdminHasNoBypass() {
        subscribe("users", "v1");

        for (int i = 0; i < 3; i++) {
            webTestClient.get()
                    .uri("/runtime/apis/users/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                    .exchange()
                    .expectStatus().isEqualTo(201);
        }
        webTestClient.get()
                .uri("/runtime/apis/users/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMIT_EXCEEDED");

        for (int i = 0; i < 3; i++) {
            webTestClient.get()
                    .uri("/runtime/apis/users/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.developer())
                    .exchange()
                    .expectStatus().isEqualTo(201);
        }
        webTestClient.get()
                .uri("/runtime/apis/users/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.developer())
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void ttlIsEstablishedOnTheFirstRequestAndIsNotRefreshedByLaterRequests() throws InterruptedException {
        subscribe("ttl", "v1");
        String token = GatewayTestJwt.admin();
        String key = "rate_limit:1:/ttl:v1";

        webTestClient.get()
                .uri("/runtime/apis/ttl/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(201);
        assertThat(redisTemplate.hasKey(key)).isTrue();
        Long ttlAfterFirst = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlAfterFirst).isBetween(1L, 60_000L);

        Thread.sleep(400);

        for (int i = 0; i < 2; i++) {
            webTestClient.get()
                    .uri("/runtime/apis/ttl/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isEqualTo(201);
        }
        Long ttlAfterLast = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlAfterLast).isLessThan(ttlAfterFirst);
        assertThat(ttlAfterLast).isBetween(1L, 60_000L);
    }

    @Test
    void unsubscribedRequestIsRejectedBeforeAnyRateLimitSlotIsConsumed() {
        webTestClient.get()
                .uri("/runtime/apis/order/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(403)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_REQUIRED");

        assertThat(redisTemplate.hasKey("rate_limit:1:/order:v1")).isFalse();
        assertThat(MANAGED_HITS.get()).isZero();
    }

    @Test
    void invalidJwtReturns401BeforeAnyRateLimitSlotIsConsumed() {
        webTestClient.get()
                .uri("/runtime/apis/order/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.expiredAdmin())
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED");

        assertThat(redisTemplate.hasKey("rate_limit:1:/order:v1")).isFalse();
        assertThat(MANAGED_HITS.get()).isZero();
    }

    @Test
    void unavailableSubscriptionCheckReturns503BeforeRateLimiting() {
        CHECK_UNAVAILABLE.set(true);

        webTestClient.get()
                .uri("/runtime/apis/orderc/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_SERVICE_UNAVAILABLE");

        assertThat(redisTemplate.hasKey("rate_limit:1:/orderc:v1")).isFalse();
        assertThat(MANAGED_HITS.get()).isZero();
    }

    @Test
    void managementRoutesAreNotRateLimited() {
        for (int i = 0; i < 5; i++) {
            webTestClient.get()
                    .uri("/apis/list")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                    .exchange()
                    .expectStatus().isEqualTo(201);
        }
    }

    @Test
    void allowedRequestForwardsTheAuthorizationHeaderUnchanged() {
        subscribe("headers", "v1");
        String token = GatewayTestJwt.admin();

        webTestClient.get()
                .uri("/runtime/apis/headers/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(201);

        Map<String, List<String>> headers = MANAGED_HEADERS.get();
        assertThat(headers).isNotNull();
        List<String> forwarded = headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(HttpHeaders.AUTHORIZATION))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
        assertThat(forwarded).containsExactly("Bearer " + token);
    }
}