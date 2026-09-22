package com.openbank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "JWT_SECRET=" + GatewayTestJwt.SECRET)
@AutoConfigureWebTestClient
class GatewayHealthEndpointTest {

    @Autowired
    WebTestClient webTestClient;

    @Test
    void healthEndpointIsOpenAndReportsUp() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void actuatorExposesOnlyHealthAndNotGatewayInternals() {
        webTestClient.get()
                .uri("/actuator")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$._links.health").exists()
                .jsonPath("$._links.gateway").doesNotExist();

        webTestClient.get()
                .uri("/actuator/gateway/routes")
                .exchange()
                .expectStatus().isNotFound();
    }
}