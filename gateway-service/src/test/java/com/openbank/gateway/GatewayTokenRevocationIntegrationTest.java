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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "JWT_SECRET=" + GatewayTestJwt.SECRET)
@AutoConfigureWebTestClient
class GatewayTokenRevocationIntegrationTest {

    private static final AtomicInteger PAYMENT_HITS = new AtomicInteger(0);

    private static final HttpServer PAYMENT_SERVER = startServer("payment");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no");

    @DynamicPropertySource
    static void redisAndUpstream(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
        registry.add("PAYMENT_SERVICE_URL", () -> "http://127.0.0.1:" + PAYMENT_SERVER.getAddress().getPort());
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
        PAYMENT_HITS.incrementAndGet();
        byte[] responseBytes = ("{\"upstream\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }

    @AfterAll
    static void stopServers() {
        PAYMENT_SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        PAYMENT_HITS.set(0);
        stringRedisTemplate.keys("token_revocation:jti:*").forEach(stringRedisTemplate::delete);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void activeJwtWithJtiIsForwardedToTheUpstream() {
        String token = GatewayTestJwt.adminWithJti("active-jti");

        webTestClient.get()
                .uri("/accounts/101")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("payment");

        assertThat(PAYMENT_HITS.get()).isEqualTo(1);
    }

    @Test
    void revokedJwtIsRejectedWith401BeforeReachingTheUpstream() {
        stringRedisTemplate.opsForValue().set("token_revocation:jti:revoked-jti", "1");
        String token = GatewayTestJwt.adminWithJti("revoked-jti");

        byte[] body = webTestClient.get()
                .uri("/accounts/102")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized")
                .jsonPath("$.path").isEqualTo("/accounts/102")
                .jsonPath("$.code").isEqualTo("TOKEN_REVOKED")
                .jsonPath("$.message").isEqualTo("Token has been revoked")
                .jsonPath("$.fieldErrors").isEmpty()
                .returnResult()
                .getResponseBody();

        String text = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(body)).toString();
        assertThat(text)
                .doesNotContain(token)
                .doesNotContain("revoked-jti")
                .doesNotContain("com.openbank")
                .doesNotContain("redis")
                .doesNotContain("localhost")
                .doesNotContain("127.0.0.1");
        assertThat(PAYMENT_HITS.get()).isZero();
    }

    @Test
    void revokedJwtIsRejectedOnRuntimeRoutesBeforeAnyEnforcement() {
        stringRedisTemplate.opsForValue().set("token_revocation:jti:runtime-revoked", "1");

        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.adminWithJti("runtime-revoked"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("TOKEN_REVOKED");

        assertThat(PAYMENT_HITS.get()).isZero();
    }

    @Test
    void jwtWithoutJtiIsHandledAccordingToTheExistingContract() {
        webTestClient.get()
                .uri("/accounts/103")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("payment");

        assertThat(PAYMENT_HITS.get()).isEqualTo(1);
    }
}