package com.openbank.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab",
        "spring.data.redis.password=" + RedisConnectivityIntegrationTest.TEST_REDIS_PASSWORD
})
class RedisConnectivityIntegrationTest {

    static final String TEST_REDIS_PASSWORD = "integration-test-redis-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no",
                    "--requirepass", TEST_REDIS_PASSWORD);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisProperties redisProperties;

    @Test
    void applicationConnectsToRedisAndPingSucceeds() {
        String pong = stringRedisTemplate.execute((RedisConnection connection) -> connection.ping());
        assertThat(pong).isEqualTo("PONG");
    }

    @Test
    void redisHealthGroupReportsUpWhenRedisIsAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health/redisHealth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }

    @Test
    void redisPasswordIsBoundButNeverExposedThroughHealth() throws Exception {
        assertThat(redisProperties.getPassword()).isEqualTo(TEST_REDIS_PASSWORD);

        String redisHealthBody = mockMvc.perform(get("/actuator/health/redisHealth"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(redisHealthBody).doesNotContain(TEST_REDIS_PASSWORD);

        String defaultHealthBody = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(defaultHealthBody).doesNotContain(TEST_REDIS_PASSWORD);
    }
}