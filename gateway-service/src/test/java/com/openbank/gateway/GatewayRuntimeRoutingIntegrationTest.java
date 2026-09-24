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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=" + GatewayTestJwt.SECRET
        })
@AutoConfigureWebTestClient
class GatewayRuntimeRoutingIntegrationTest {

    record CapturedRequest(String method, String path, String query,
                           Map<String, List<String>> headers, String body) {
    }

    private static final Set<String> SUBSCRIPTIONS = new HashSet<>();
    private static final AtomicInteger MANAGED_HITS = new AtomicInteger(0);
    private static final AtomicReference<CapturedRequest> MANAGED = new AtomicReference<>();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no");

    private static final HttpServer API_MANAGEMENT_SERVER = startServer("api-management");
    private static final HttpServer MANAGED_SERVER = startServer("managed");

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
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
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if ("api-management".equals(name) && "/internal/subscription-check".equals(path)) {
            Map<String, String> params = parseQuery(query);
            boolean subscribed = SUBSCRIPTIONS.contains(
                    params.getOrDefault("contextPath", "") + "|" + params.getOrDefault("version", ""));
            respond(exchange, 200, "{\"subscribed\":" + subscribed + ","
                    + "\"tierId\":1,\"tierName\":\"Gold\","
                    + "\"requestsPerWindow\":1000,\"windowSeconds\":60}");
            return;
        }
        if ("managed".equals(name)) {
            MANAGED_HITS.incrementAndGet();
            Map<String, List<String>> headers = exchange.getRequestHeaders().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            MANAGED.set(new CapturedRequest(method, path, query, headers, body));
            respond(exchange, 201,
                    "{\"upstream\":\"managed\",\"method\":\"" + method + "\",\"path\":\"" + path + "\"}");
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
        MANAGED.set(null);
    }

    @Autowired
    WebTestClient webTestClient;

    private void subscribe(String context, String version) {
        SUBSCRIPTIONS.add("/" + context + "|" + version);
    }

    @Test
    void subscribedRuntimeRequestIsForwardedToTheConfiguredUpstream() {
        subscribe("payments", "v1");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("managed");

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(captured.method()).isEqualTo("GET");
        assertThat(captured.path()).isEqualTo("/runtime/apis/payments/v1/orders");
    }

    @Test
    void httpMethodAndRequestBodyArePreserved() {
        subscribe("payments", "v1");

        webTestClient.post()
                .uri("/runtime/apis/payments/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .bodyValue("{\"amount\":100,\"currency\":\"USD\"}")
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.method").isEqualTo("POST");

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.body()).isEqualTo("{\"amount\":100,\"currency\":\"USD\"}");
    }

    @Test
    void queryParametersArePreserved() {
        subscribe("payments", "v1");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/orders?trace=xyz&limit=5&asc=true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201);

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(captured.query()).isEqualTo("trace=xyz&limit=5&asc=true");
    }

    @Test
    void relevantHeadersArePreserved() {
        subscribe("payments", "v1");
        String token = GatewayTestJwt.admin();

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Correlation-Id", "corr-001")
                .header(HttpHeaders.ACCEPT, "application/json")
                .exchange()
                .expectStatus().isEqualTo(201);

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();

        List<String> auth = values(captured, HttpHeaders.AUTHORIZATION);
        assertThat(auth).containsExactly("Bearer " + token);

        List<String> correlation = values(captured, "X-Correlation-Id");
        assertThat(correlation).containsExactly("corr-001");

        List<String> accept = values(captured, HttpHeaders.ACCEPT);
        assertThat(accept).containsExactly("application/json");
    }

    private static List<String> values(CapturedRequest captured, String headerName) {
        return captured.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(headerName))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }

    @Test
    void runtimePathIsForwardedIntactWithoutRewriting() {
        subscribe("payments", "v1");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/orders/items/line-7/details")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.path").isEqualTo("/runtime/apis/payments/v1/orders/items/line-7/details");

        assertThat(MANAGED.get().path()).isEqualTo("/runtime/apis/payments/v1/orders/items/line-7/details");
    }

    @Test
    void clientCannotChooseTheUpstreamThroughQueryParameters() {
        subscribe("payments", "v2");

        webTestClient.get()
                .uri("/runtime/apis/payments/v2/orders"
                        + "?target=http://127.0.0.1:1/&upstream=http://evil.example:99"
                        + "&MANAGED_API_TARGET_URL=http://127.0.0.1:1/")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("managed");

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(captured.path()).isEqualTo("/runtime/apis/payments/v2/orders");
        assertThat(captured.query()).contains("target=http://127.0.0.1:1/");
        assertThat(captured.query()).contains("upstream=http://evil.example:99");
        assertThat(captured.query()).contains("MANAGED_API_TARGET_URL=http://127.0.0.1:1/");
    }

    @Test
    void clientCannotChooseTheUpstreamThroughHeaders() {
        subscribe("payments", "v2");

        webTestClient.get()
                .uri("/runtime/apis/payments/v2/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .header("X-Runtime-Upstream", "http://127.0.0.1:1/")
                .header("X-Upstream-Target", "http://evil.example:99")
                .header("X-Host", "evil.example")
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("managed");

        assertThat(MANAGED.get()).isNotNull();
    }

    @Test
    void clientCannotOverrideTheUpstreamThroughPathInput() {
        subscribe("payments", "v1");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/orders/evil.example.com/x")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("managed");

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(captured.path()).isEqualTo("/runtime/apis/payments/v1/orders/evil.example.com/x");
    }

    @Test
    void invalidJwtReturns401BeforeForwarding() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.expiredAdmin())
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED");

        assertThat(MANAGED_HITS.get()).isZero();
    }

    @Test
    void unsubscribedRequestReturns403AndIsNotForwarded() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(403)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_REQUIRED");

        assertThat(MANAGED_HITS.get()).isZero();
    }

    @Test
    void managementRoutesRemainUnaffected() {
        webTestClient.get()
                .uri("/apis/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("api-management");
    }
}