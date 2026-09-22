package com.openbank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayUpstreamFailureTest {

    @DynamicPropertySource
    static void unreachableUpstreams(DynamicPropertyRegistry registry) {
        String unreachableUrl = "http://127.0.0.1:" + freePort();
        registry.add("IDENTITY_SERVICE_URL", () -> unreachableUrl);
        registry.add("API_MANAGEMENT_SERVICE_URL", () -> unreachableUrl);
        registry.add("PAYMENT_SERVICE_URL", () -> unreachableUrl);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("failed to allocate a free port", e);
        }
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    void unreachableUpstreamReturnsServiceUnavailableWithSafeErrorBody() {
        byte[] body = webTestClient.get()
                .uri("/accounts/1")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Service Unavailable")
                .jsonPath("$.code").isEqualTo("UPSTREAM_SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("The requested service is currently unavailable")
                .returnResult()
                .getResponseBody();

        assertThat(StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(body)).toString())
                .doesNotContain("127.0.0.1")
                .doesNotContain("localhost")
                .doesNotContain("ConnectException")
                .doesNotContain("java.")
                .doesNotContain("http://");
    }

    @Test
    void healthEndpointStillReportsUpWhenUpstreamsAreDown() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void unroutedPathsStillReturnNotFoundWhenUpstreamsAreDown() {
        webTestClient.get()
                .uri("/no-such-path")
                .exchange()
                .expectStatus().isNotFound();
    }
}