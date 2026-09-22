package com.openbank.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6399",
        "spring.data.redis.password="
})
class RedisConfigurationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private LettuceConnectionFactory connectionFactory;

    @Autowired
    private MockMvc mockMvc;

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
    void defaultHealthAggregateReportsUpWithoutRedisRunning() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void redisHealthGroupDistinguishesAnUnreachableRedisAsDown() throws Exception {
        mockMvc.perform(get("/actuator/health/redisHealth"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.redis.status").value("DOWN"));
    }
}