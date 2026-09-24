package com.openbank.gateway;

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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRuntimeIdentityHeaderIntegrationTest {

    record CapturedRequest(String name, String method, String path, String query,
                           Map<String, List<String>> headers, String body) {
    }

    private static final String CLIENT_ID = "client-integration-0001";
    private static final String CLIENT_SECRET = "integration-super-secret";

    private static final String AUTHENTICATED_BODY = "{\"authenticated\":true,\"applicationId\":7,\"ownerUserId\":42,"
            + "\"subscribed\":true,\"tierId\":1,\"tierName\":\"Gold\","
            + "\"requestsPerWindow\":1000,\"windowSeconds\":3600}";

    private static final AtomicReference<String> CREDENTIAL_BODY = new AtomicReference<>(
            AUTHENTICATED_BODY);
    private static final AtomicInteger CREDENTIAL_STATUS = new AtomicInteger(200);

    private static final AtomicReference<CapturedRequest> MANAGED = new AtomicReference<>();
    private static final AtomicReference<CapturedRequest> MANAGEMENT_CAPTURE = new AtomicReference<>();

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
            respond(exchange, CREDENTIAL_STATUS.get(), CREDENTIAL_BODY.get());
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
        if ("api-management".equals(name)) {
            MANAGEMENT_CAPTURE.set(new CapturedRequest(name, method, path,
                    exchange.getRequestURI().getRawQuery(), headers, body));
            respond(exchange, 201, "{\"upstream\":\"api-management\"}");
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

    @BeforeEach
    void reset() {
        CREDENTIAL_BODY.set(AUTHENTICATED_BODY);
        CREDENTIAL_STATUS.set(200);
        MANAGED.set(null);
        MANAGEMENT_CAPTURE.set(null);
    }

    @Autowired
    WebTestClient webTestClient;

    private String basicHeader() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> headerValue(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }

    @Test
    void jwtRuntimeRequestDeliversTrustedIdentityHeadersUpstream() {
        String token = GatewayTestJwt.admin();

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("managed");

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(captured.path()).isEqualTo("/runtime/apis/payments/v1/accounts");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.USER_ID)).containsExactly("1");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.ROLES)).containsExactly("ADMIN");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.APPLICATION_ID)).isEmpty();
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.CLIENT_ID)).isEmpty();
        assertThat(headerValue(captured.headers(), HttpHeaders.AUTHORIZATION))
                .containsExactly("Bearer " + token);
    }

    @Test
    void clientCredentialRuntimeRequestDeliversTrustedIdentityHeadersUpstream() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("managed");

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(captured.path()).isEqualTo("/runtime/apis/payments/v1/accounts");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.USER_ID)).containsExactly("42");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.APPLICATION_ID)).containsExactly("7");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.CLIENT_ID)).containsExactly(CLIENT_ID);
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.ROLES)).isEmpty();
        assertThat(headerValue(captured.headers(), HttpHeaders.AUTHORIZATION)).isEmpty();
    }

    @Test
    void spoofedTrustedHeadersCannotOverrideJwtValues() {
        String token = GatewayTestJwt.admin();

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(TrustedIdentityHeaders.USER_ID, "attacker")
                .header(TrustedIdentityHeaders.ROLES, "attacker-role")
                .header(TrustedIdentityHeaders.APPLICATION_ID, "attacker-app")
                .header(TrustedIdentityHeaders.CLIENT_ID, "attacker-client")
                .exchange()
                .expectStatus().isEqualTo(201);

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.USER_ID)).containsExactly("1");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.ROLES)).containsExactly("ADMIN");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.APPLICATION_ID)).isEmpty();
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.CLIENT_ID)).isEmpty();
    }

    @Test
    void spoofedTrustedHeadersCannotOverrideClientCredentialValues() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .header(TrustedIdentityHeaders.USER_ID, "attacker")
                .header(TrustedIdentityHeaders.ROLES, "ADMIN")
                .header(TrustedIdentityHeaders.APPLICATION_ID, "attacker-app")
                .header(TrustedIdentityHeaders.CLIENT_ID, "attacker-client")
                .exchange()
                .expectStatus().isEqualTo(201);

        CapturedRequest captured = MANAGED.get();
        assertThat(captured).isNotNull();
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.USER_ID)).containsExactly("42");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.APPLICATION_ID)).containsExactly("7");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.CLIENT_ID)).containsExactly(CLIENT_ID);
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.ROLES)).isEmpty();
    }

    @Test
    void identityPropagationPreservesMethodQueryBodyAndUnrelatedHeaders() {
        String token = GatewayTestJwt.admin();

        webTestClient.post()
                .uri("/runtime/apis/payments/v1/transfers?trace=xyz")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Custom-Header", "custom-value")
                .bodyValue("{\"amount\":123}")
                .exchange()
                .expectStatus().isEqualTo(201);

        CapturedRequest jwtCaptured = MANAGED.get();
        assertThat(jwtCaptured).isNotNull();
        assertThat(jwtCaptured.method()).isEqualTo("POST");
        assertThat(jwtCaptured.path()).isEqualTo("/runtime/apis/payments/v1/transfers");
        assertThat(jwtCaptured.query()).isEqualTo("trace=xyz");
        assertThat(jwtCaptured.body()).isEqualTo("{\"amount\":123}");
        assertThat(headerValue(jwtCaptured.headers(), "X-Custom-Header")).containsExactly("custom-value");
        assertThat(headerValue(jwtCaptured.headers(), TrustedIdentityHeaders.USER_ID)).containsExactly("1");

        webTestClient.post()
                .uri("/runtime/apis/payments/v1/transfers?trace=yzz")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .header("X-Custom-Header", "custom-value-2")
                .bodyValue("{\"amount\":456}")
                .exchange()
                .expectStatus().isEqualTo(201);

        CapturedRequest basicCaptured = MANAGED.get();
        assertThat(basicCaptured).isNotNull();
        assertThat(basicCaptured.method()).isEqualTo("POST");
        assertThat(basicCaptured.path()).isEqualTo("/runtime/apis/payments/v1/transfers");
        assertThat(basicCaptured.query()).isEqualTo("trace=yzz");
        assertThat(basicCaptured.body()).isEqualTo("{\"amount\":456}");
        assertThat(headerValue(basicCaptured.headers(), "X-Custom-Header")).containsExactly("custom-value-2");
        assertThat(headerValue(basicCaptured.headers(), TrustedIdentityHeaders.USER_ID)).containsExactly("42");
    }

    @Test
    void nonRuntimeManagementRouteDoesNotReceiveTrustedIdentityHeaders() {
        webTestClient.get()
                .uri("/apis/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("api-management");

        CapturedRequest captured = MANAGEMENT_CAPTURE.get();
        assertThat(captured).isNotNull();
        assertThat(captured.path()).isEqualTo("/apis/list");
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.USER_ID)).isEmpty();
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.ROLES)).isEmpty();
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.APPLICATION_ID)).isEmpty();
        assertThat(headerValue(captured.headers(), TrustedIdentityHeaders.CLIENT_ID)).isEmpty();
    }

    @Test
    void invalidJwtDoesNotReachTheManagedApiWithTrustedHeaders() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + GatewayTestJwt.tampered(GatewayTestJwt.admin()))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED");

        assertThat(MANAGED.get()).isNull();
    }

    @Test
    void invalidClientCredentialsDoNotReachTheManagedApiWithTrustedHeaders() {
        CREDENTIAL_STATUS.set(401);
        CREDENTIAL_BODY.set("{\"authenticated\":false}");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, basicHeader())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("CLIENT_CREDENTIAL_INVALID");

        assertThat(MANAGED.get()).isNull();
    }
}