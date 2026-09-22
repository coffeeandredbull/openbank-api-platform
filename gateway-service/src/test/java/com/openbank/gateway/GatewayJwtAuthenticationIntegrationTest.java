package com.openbank.gateway;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.openbank.gateway.filter.GatewayGlobalFilter;
import com.openbank.gateway.filter.JwtAuthenticationFilter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
class GatewayJwtAuthenticationIntegrationTest {

    record CapturedRequest(String name, String method, String path, String query,
                           Map<String, List<String>> headers, String body) {
    }

    private static final AtomicReference<CapturedRequest> CAPTURED = new AtomicReference<>();

    private static final HttpServer IDENTITY_SERVER = startServer("identity");
    private static final HttpServer API_MANAGEMENT_SERVER = startServer("api-management");
    private static final HttpServer PAYMENT_SERVER = startServer("payment");

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("IDENTITY_SERVICE_URL", () -> "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort());
        registry.add("API_MANAGEMENT_SERVICE_URL", () -> "http://127.0.0.1:" + API_MANAGEMENT_SERVER.getAddress().getPort());
        registry.add("PAYMENT_SERVICE_URL", () -> "http://127.0.0.1:" + PAYMENT_SERVER.getAddress().getPort());
        registry.add("JWT_SECRET", () -> GatewayTestJwt.SECRET);
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
        Map<String, List<String>> headers = exchange.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        CAPTURED.set(new CapturedRequest(name, method, path, query, headers, body));

        int status;
        String responseBody;
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (path.startsWith("/apis/") && lastSegment.matches("\\d+")) {
            status = Integer.parseInt(lastSegment);
            String code = switch (status) {
                case 401 -> "AUTHENTICATION_FAILED";
                case 403 -> "ACCESS_DENIED";
                case 404 -> "APPLICATION_NOT_FOUND";
                case 409 -> "SUBSCRIPTION_ALREADY_EXISTS";
                default -> "BACKEND_INTERNAL_ERROR";
            };
            responseBody = "{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"backend says " + status + "\"}";
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
        IDENTITY_SERVER.stop(0);
        API_MANAGEMENT_SERVER.stop(0);
        PAYMENT_SERVER.stop(0);
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    void postUsersIsPublicAndForwardedWithoutJwt() {
        webTestClient.post()
                .uri("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"alice\"}")
                .exchange()
                .expectStatus().isCreated();
        assertThat(CAPTURED.get().name()).isEqualTo("identity");
        assertThat(CAPTURED.get().method()).isEqualTo("POST");
        assertThat(CAPTURED.get().path()).isEqualTo("/users");
    }

    @Test
    void postAuthLoginIsPublicAndForwardedWithoutJwt() {
        webTestClient.post()
                .uri("/auth/login")
                .bodyValue("{\"email\":\"alice@example.com\"}")
                .exchange()
                .expectStatus().isCreated();
        assertThat(CAPTURED.get().name()).isEqualTo("identity");
        assertThat(CAPTURED.get().path()).isEqualTo("/auth/login");
    }

    @Test
    void healthEndpointIsPublicWithoutJwt() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void protectedRequestWithoutAuthorizationReturns401() {
        webTestClient.get()
                .uri("/accounts/301")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized")
                .jsonPath("$.path").isEqualTo("/accounts/301")
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.message").isEqualTo("Authentication is required");
    }

    @Test
    void wrongAuthorizationSchemeReturns401() {
        assertRejected(webTestClient.get().uri("/accounts/302")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"));
    }

    @Test
    void malformedBearerTokenReturns401() {
        assertRejected(webTestClient.get().uri("/accounts/303")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"));
    }

    @Test
    void tamperedTokenReturns401() {
        assertRejected(webTestClient.get().uri("/accounts/304")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.tampered(GatewayTestJwt.admin())));
    }

    @Test
    void expiredTokenReturns401() {
        assertRejected(webTestClient.get().uri("/accounts/305")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.expiredAdmin()));
    }

    @Test
    void tokenWithoutSubjectReturns401() {
        assertRejected(webTestClient.get().uri("/accounts/306")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.missingSub()));
    }

    @Test
    void tokenWithoutRoleClaimReturns401() {
        assertRejected(webTestClient.get().uri("/accounts/307")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.missingRole()));
    }

    @Test
    void tokenWithUnknownRoleReturns401() {
        assertRejected(webTestClient.get().uri("/accounts/308")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.invalidRole()));
    }

    @Test
    void identityRoutesArePublicOnlyForRegistrationAndLogin() {
        webTestClient.get().uri("/users/me").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/auth/login").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/users").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void validAdminTokenForwardsProtectedRequest() {
        webTestClient.get()
                .uri("/apis/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("api-management")
                .jsonPath("$.method").isEqualTo("GET")
                .jsonPath("$.path").isEqualTo("/apis/list");
        assertThat(CAPTURED.get().method()).isEqualTo("GET");
        assertThat(CAPTURED.get().path()).isEqualTo("/apis/list");
    }

    @Test
    void validDeveloperTokenForwardsProtectedRequest() {
        webTestClient.get()
                .uri("/payments/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.developer())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("payment");
        assertThat(CAPTURED.get().name()).isEqualTo("payment");
        assertThat(CAPTURED.get().path()).isEqualTo("/payments/list");
    }

    @Test
    void authorizationHeaderIsPreservedToUpstream() {
        String token = GatewayTestJwt.admin();
        webTestClient.get()
                .uri("/accounts/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isCreated();
        List<List<String>> forwarded = CAPTURED.get().headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(HttpHeaders.AUTHORIZATION))
                .map(Map.Entry::getValue)
                .toList();
        assertThat(forwarded).isNotEmpty();
        assertThat(forwarded.get(0)).contains("Bearer " + token);
    }

    @Test
    void jwtIsNotExposedInTheResponse() {
        String token = GatewayTestJwt.admin();
        byte[] responseBytes = webTestClient.get()
                .uri("/credentials/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
        String body = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(responseBytes)).toString();
        assertThat(body).doesNotContain(token);
    }

    @Test
    void protectedForwardingPreservesMethodQueryBodyAndHeaders() {
        String token = GatewayTestJwt.admin();
        webTestClient.post()
                .uri("/payments/with-body?trace=xyz")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Custom-Header", "custom-value")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue("{\"amount\":123}")
                .exchange()
                .expectStatus().isCreated();

        CapturedRequest captured = CAPTURED.get();
        assertThat(captured.name()).isEqualTo("payment");
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.path()).isEqualTo("/payments/with-body");
        assertThat(captured.query()).isEqualTo("trace=xyz");
        assertThat(captured.body()).isEqualTo("{\"amount\":123}");
        assertThat(captured.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("X-Custom-Header"))
                .flatMap(entry -> entry.getValue().stream()))
                .contains("custom-value");
    }

    @Test
    void backendErrorsPassThroughUnchangedWithAValidJwt() {
        webTestClient.get()
                .uri("/apis/401")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTHENTICATION_FAILED")
                .jsonPath("$.message").isEqualTo("backend says 401")
                .jsonPath("$.status").isEqualTo(401);

        webTestClient.get()
                .uri("/apis/403")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("ACCESS_DENIED");

        webTestClient.get()
                .uri("/apis/404")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("APPLICATION_NOT_FOUND");

        webTestClient.get()
                .uri("/apis/409")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SUBSCRIPTION_ALREADY_EXISTS");

        webTestClient.get()
                .uri("/apis/500")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.code").isEqualTo("BACKEND_INTERNAL_ERROR");
    }

    @Test
    void rejectedResponsesNeverExposeJwtOrParsingInternals() {
        String token = GatewayTestJwt.tampered(GatewayTestJwt.admin());
        byte[] body = webTestClient.get()
                .uri("/accounts/309")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .returnResult()
                .getResponseBody();
        String text = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(body)).toString();
        assertThat(text)
                .doesNotContain(token)
                .doesNotContain("InvalidJwtException")
                .doesNotContain("Nimbus")
                .doesNotContain("jose")
                .doesNotContain("java.")
                .doesNotContain("com.openbank")
                .doesNotContain("localhost")
                .doesNotContain("127.0.0.1");
    }

    @Test
    void authorizationValuesAreNeverLogged() {
        ch.qos.logback.classic.Logger authLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(JwtAuthenticationFilter.class);
        ch.qos.logback.classic.Logger gatewayLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GatewayGlobalFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        String validToken = GatewayTestJwt.admin();
        String rejectedToken = GatewayTestJwt.tampered(GatewayTestJwt.admin());
        try {
            authLogger.addAppender(appender);
            gatewayLogger.addAppender(appender);

            webTestClient.get()
                    .uri("/accounts/list")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                    .exchange()
                    .expectStatus().isCreated();
            webTestClient.get()
                    .uri("/accounts/309")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + rejectedToken)
                    .exchange()
                    .expectStatus().isUnauthorized();
        } finally {
            authLogger.detachAppender(appender);
            gatewayLogger.detachAppender(appender);
        }
        assertThat(appender.list)
                .noneMatch(event -> event.getFormattedMessage().contains("Bearer"))
                .noneMatch(event -> event.getFormattedMessage().contains(validToken))
                .noneMatch(event -> event.getFormattedMessage().contains(rejectedToken));
    }

    private void assertRejected(WebTestClient.RequestHeadersSpec<?> request) {
        request.exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.message").isEqualTo("Authentication is required");
    }
}