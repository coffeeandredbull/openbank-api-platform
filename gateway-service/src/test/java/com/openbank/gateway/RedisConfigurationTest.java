package com.openbank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.web.reactive.server.WebTestClient;

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
class RedisConfigurationTest {

    @Autowired
    private LettuceConnectionFactory connectionFactory;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void redisConfigurationBindsHostAndPortFromConfiguration() {
        assertThat(connectionFactory.getStandaloneConfiguration().getHostName()).isEqualTo("localhost");
        assertThat(connectionFactory.getStandaloneConfiguration().getPort()).isEqualTo(6399);
    }

    @Test
    void emptyOrMissingRedisPasswordDoesNotBecomeALiteralPassword() {
        assertThat(connectionFactory.getStandaloneConfiguration().getPassword().isPresent()).isFalse();
    }

    @Test
    void defaultHealthAggregateReportsUpWithoutRedisRunning() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void redisHealthGroupDistinguishesAnUnreachableRedisAsDown() {
        webTestClient.get()
                .uri("/actuator/health/redisHealth")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DOWN")
                .jsonPath("$.components.redis.status").isEqualTo("DOWN");
    }
}