package com.openbank.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRoutingIntegrationTest {

    record CapturedRequest(String method, String path, String query, Map<String, List<String>> headers, String body) {
    }

    static final AtomicReference<CapturedRequest> IDENTITY = new AtomicReference<>();
    static final AtomicReference<CapturedRequest> API_MANAGEMENT = new AtomicReference<>();
    static final AtomicReference<CapturedRequest> PAYMENT = new AtomicReference<>();

    static final HttpServer IDENTITY_SERVER = startServer("identity", IDENTITY);
    static final HttpServer API_MANAGEMENT_SERVER = startServer("api-management", API_MANAGEMENT);
    static final HttpServer PAYMENT_SERVER = startServer("payment", PAYMENT);

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("IDENTITY_SERVICE_URL", () -> "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort());
        registry.add("API_MANAGEMENT_SERVICE_URL", () -> "http://127.0.0.1:" + API_MANAGEMENT_SERVER.getAddress().getPort());
        registry.add("PAYMENT_SERVICE_URL", () -> "http://127.0.0.1:" + PAYMENT_SERVER.getAddress().getPort());
        registry.add("JWT_SECRET", () -> GatewayTestJwt.SECRET);
    }

    private static final String VALID_TOKEN = GatewayTestJwt.admin();

    private static HttpServer startServer(String name, AtomicReference<CapturedRequest> target) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handle(name, exchange, target));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start test upstream " + name, e);
        }
    }

    private static void handle(String name, HttpExchange exchange, AtomicReference<CapturedRequest> target)
            throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, List<String>> headers = exchange.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        target.set(new CapturedRequest(method, path, query, headers, body));

        int status;
        String responseBody;
        if ("/apis/999".equals(path)) {
            status = 404;
            responseBody = "{\"code\":\"APPLICATION_NOT_FOUND\",\"message\":\"not found\"}";
        } else {
            status = 201;
            responseBody = "{\"upstream\":\"" + name + "\",\"method\":\"" + method + "\",\"path\":\"" + path + "\"}";
        }

        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }

    @AfterAll
    static void stopServers() {
        stop(IDENTITY_SERVER);
        stop(API_MANAGEMENT_SERVER);
        stop(PAYMENT_SERVER);
    }

    private static void stop(HttpServer server) {
        if (server != null) {
            server.stop(0);
        }
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    void identityRouteForwardsMethodPathQueryBodyAndHeadersUntouched() {
        webTestClient.post()
                .uri("/users?trace=abc")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Test-Header", "through-the-gateway")
                .bodyValue("{\"username\":\"alice\",\"password\":\"test-placeholder\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("identity")
                .jsonPath("$.method").isEqualTo("POST")
                .jsonPath("$.path").isEqualTo("/users");

        CapturedRequest captured = IDENTITY.get();
        assertThat(captured).isNotNull();
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.path()).isEqualTo("/users");
        assertThat(captured.query()).isEqualTo("trace=abc");
        assertThat(captured.body()).isEqualTo("{\"username\":\"alice\",\"password\":\"test-placeholder\"}");
        assertThat(captured.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("X-Test-Header"))
                .flatMap(entry -> entry.getValue().stream()))
                .contains("through-the-gateway");
    }

    @Test
    void identitySubpathsAndAuthRouteForwardedWithoutRewriting() {
        webTestClient.get()
                .uri("/users/42")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .exchange()
                .expectStatus().isCreated();
        assertThat(IDENTITY.get().path()).isEqualTo("/users/42");

        webTestClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\"}")
                .exchange()
                .expectStatus().isCreated();
        assertThat(IDENTITY.get().path()).isEqualTo("/auth/login");
    }

    @Test
    void apiManagementRoutesForwardRequestsUntouched() {
        webTestClient.get()
                .uri("/apis/123")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .exchange()
                .expectStatus().isCreated();
        assertThat(API_MANAGEMENT.get().method()).isEqualTo("GET");
        assertThat(API_MANAGEMENT.get().path()).isEqualTo("/apis/123");

        webTestClient.get()
                .uri("/applications/5")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .exchange()
                .expectStatus().isCreated();
        assertThat(API_MANAGEMENT.get().path()).isEqualTo("/applications/5");
    }

    @Test
    void paymentRouteForwardsRequestUntouched() {
        webTestClient.get()
                .uri("/accounts/7")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .exchange()
                .expectStatus().isCreated();
        assertThat(PAYMENT.get().method()).isEqualTo("GET");
        assertThat(PAYMENT.get().path()).isEqualTo("/accounts/7");
    }

    @Test
    void backendApplicationErrorsPassThroughUnchanged() {
        webTestClient.get()
                .uri("/apis/999")
                .header("Authorization", "Bearer " + VALID_TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("APPLICATION_NOT_FOUND")
                .jsonPath("$.message").isEqualTo("not found");
    }

    @Test
    void unroutedPathsReturnGateway404() {
        webTestClient.get()
                .uri("/no-such-route")
                .exchange()
                .expectStatus().isNotFound();
    }
}