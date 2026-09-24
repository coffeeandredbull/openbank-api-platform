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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=" + GatewayTestJwt.SECRET,
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6399",
                "spring.data.redis.password="
        })
@AutoConfigureWebTestClient
class GatewayTokenRevocationUnavailableIntegrationTest {

    private static final AtomicInteger PAYMENT_HITS = new AtomicInteger(0);

    private static final HttpServer PAYMENT_SERVER = startServer("payment");

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
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
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void unreachableRevocationStoreFailsClosedWith503AndTheRequestIsNotForwarded() {
        String token = GatewayTestJwt.adminWithJti("some-jti");

        byte[] body = webTestClient.get()
                .uri("/accounts/110")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Service Unavailable")
                .jsonPath("$.path").isEqualTo("/accounts/110")
                .jsonPath("$.code").isEqualTo("TOKEN_REVOCATION_SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Token revocation service unavailable")
                .returnResult()
                .getResponseBody();

        assertThat(StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(body)).toString())
                .doesNotContain(token)
                .doesNotContain("some-jti")
                .doesNotContain("redis")
                .doesNotContain("localhost")
                .doesNotContain("127.0.0.1")
                .doesNotContain("com.openbank")
                .doesNotContain("lettuce")
                .doesNotContain("stack");
        assertThat(PAYMENT_HITS.get()).isZero();
    }

    @Test
    void unreachableRevocationStoreFailsClosedOnRuntimeRoutesToo() {
        webTestClient.get()
                .uri("/runtime/apis/payments/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.adminWithJti("runtime-jti"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("TOKEN_REVOCATION_SERVICE_UNAVAILABLE");

        assertThat(PAYMENT_HITS.get()).isZero();
    }

    @Test
    void jwtWithoutJtiContinuesItsExistingContractWhenTheRevocationStoreIsUnavailable() {
        webTestClient.get()
                .uri("/accounts/111")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestJwt.admin())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody()
                .jsonPath("$.upstream").isEqualTo("payment");

        assertThat(PAYMENT_HITS.get()).isEqualTo(1);
    }
}