package com.openbank.gateway;

import com.openbank.gateway.ratelimit.RateLimitKeyGenerator;
import com.openbank.gateway.ratelimit.RateLimitProperties;
import com.openbank.gateway.ratelimit.RateLimitService.State;
import com.openbank.gateway.ratelimit.RedisRateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisRateLimiterDistributedTest {

    static final String TEST_REDIS_PASSWORD = "integration-test-redis-password";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no",
                    "--requirepass", TEST_REDIS_PASSWORD);

    private final RateLimitKeyGenerator keyGenerator = new RateLimitKeyGenerator();

    @BeforeEach
    void resetSharedCounters() {
        StringRedisTemplate template = newTemplate();
        template.delete(java.util.List.of(
                keyGenerator.keyFor(1, "/payments", "v1"),
                keyGenerator.keyFor(2, "/payments", "v1"),
                keyGenerator.keyFor(1, "/accounts", "v1"),
                keyGenerator.keyFor(1, "/payments", "v2")));
    }

    private StringRedisTemplate newTemplate() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        configuration.setPassword(TEST_REDIS_PASSWORD);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return new StringRedisTemplate(factory);
    }

    private RedisRateLimitService newClient(StringRedisTemplate template) {
        return new RedisRateLimitService(template, new RateLimitProperties(2, 60), keyGenerator);
    }

    @Test
    void independentClientsSharingTheSameRedisSeeTheSameDistributedCounter() {
        StringRedisTemplate templateA = newTemplate();
        StringRedisTemplate templateB = newTemplate();
        RedisRateLimitService clientA = newClient(templateA);
        RedisRateLimitService clientB = newClient(templateB);

        assertThat(clientA.evaluate(1, "/payments", "v1").state()).isEqualTo(State.ALLOWED);
        assertThat(clientA.evaluate(1, "/payments", "v1").state()).isEqualTo(State.ALLOWED);

        assertThat(clientB.evaluate(1, "/payments", "v1").state()).isEqualTo(State.DENIED);
        assertThat(clientB.evaluate(1, "/payments", "v1").state()).isEqualTo(State.DENIED);

        String key = keyGenerator.keyFor(1, "/payments", "v1");
        assertThat(templateA.hasKey(key)).isTrue();
        Long ttl = templateA.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(1L, 60L);
    }

    @Test
    void theCounterIsScopedPerUserPerApiAcrossIndependentClients() {
        RedisRateLimitService clientA = newClient(newTemplate());
        RedisRateLimitService clientB = newClient(newTemplate());

        assertThat(clientA.evaluate(1, "/payments", "v1").state()).isEqualTo(State.ALLOWED);
        assertThat(clientA.evaluate(1, "/payments", "v1").state()).isEqualTo(State.ALLOWED);

        assertThat(clientB.evaluate(2, "/payments", "v1").state()).isEqualTo(State.ALLOWED);
        assertThat(clientB.evaluate(1, "/accounts", "v1").state()).isEqualTo(State.ALLOWED);
        assertThat(clientB.evaluate(1, "/payments", "v2").state()).isEqualTo(State.ALLOWED);
    }
}