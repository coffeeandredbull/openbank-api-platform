package com.openbank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=" + GatewayTestJwt.SECRET,
                "spring.data.redis.password=" + RedisConnectivityIntegrationTest.TEST_REDIS_PASSWORD
        })
@AutoConfigureWebTestClient
class RedisConnectivityIntegrationTest {

    static final String TEST_REDIS_PASSWORD = "integration-test-redis-password";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no",
                    "--requirepass", TEST_REDIS_PASSWORD);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private org.springframework.boot.autoconfigure.data.redis.RedisProperties redisProperties;

    @Test
    void applicationConnectsToRedisAndPingSucceeds() {
        String pong = stringRedisTemplate.execute((RedisConnection connection) -> connection.ping());
        assertThat(pong).isEqualTo("PONG");
    }

    @Test
    void redisHealthGroupReportsUpWhenRedisIsAvailable() {
        webTestClient.get()
                .uri("/actuator/health/redisHealth")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.redis.status").isEqualTo("UP");
    }

    @Test
    void redisPasswordIsBoundButNeverExposedThroughHealth() {
        assertThat(redisProperties.getPassword()).isEqualTo(TEST_REDIS_PASSWORD);

        String redisHealthBody = new String(webTestClient.get()
                .uri("/actuator/health/redisHealth")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(redisHealthBody).doesNotContain(TEST_REDIS_PASSWORD);

        String defaultHealthBody = new String(webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(defaultHealthBody).doesNotContain(TEST_REDIS_PASSWORD);
    }
}