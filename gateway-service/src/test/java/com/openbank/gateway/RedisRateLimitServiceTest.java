package com.openbank.gateway;

import com.openbank.gateway.ratelimit.RateLimitKeyGenerator;
import com.openbank.gateway.ratelimit.RateLimitProperties;
import com.openbank.gateway.ratelimit.RateLimitService.Decision;
import com.openbank.gateway.ratelimit.RateLimitService.State;
import com.openbank.gateway.ratelimit.RedisRateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimitServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisRateLimitService service = new RedisRateLimitService(
            redisTemplate, new RateLimitProperties(2, 60), new RateLimitKeyGenerator());

    @BeforeEach
    void reset() {
        org.mockito.Mockito.reset(redisTemplate);
    }

    @Test
    void requestsUpToTheLimitAreAllowed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("1", "2");

        assertThat(service.evaluate(7, "/payments", "v1").state()).isEqualTo(State.ALLOWED);
        assertThat(service.evaluate(7, "/payments", "v1").state()).isEqualTo(State.ALLOWED);
    }

    @Test
    void requestBeyondTheLimitIsDeniedWithTheRemainingWindowAsRetryAfter() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("3");
        when(redisTemplate.getExpire(eq("rate_limit:7:/payments:v1"), eq(TimeUnit.SECONDS)))
                .thenReturn(25L);

        Decision decision = service.evaluate(7, "/payments", "v1");
        assertThat(decision.state()).isEqualTo(State.DENIED);
        assertThat(decision.retryAfterSeconds()).isEqualTo(25L);
    }

    @Test
    void denyFallsBackToTheFullWindowWhenTheRemainingTtlIsUnknown() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("3");
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-1L);

        Decision decision = service.evaluate(7, "/payments", "v1");
        assertThat(decision.state()).isEqualTo(State.DENIED);
        assertThat(decision.retryAfterSeconds()).isEqualTo(60L);
    }

    @Test
    void redisFailureFailsClosedAsUnavailable() {
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(service.evaluate(7, "/payments", "v1").state()).isEqualTo(State.UNAVAILABLE);
    }

    @Test
    void theCounterUsesAnAtomicIncrementScriptThatSetsExpiryOnlyOnFirstCreation() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("1");

        service.evaluate(7, "/payments", "v1");

        ArgumentCaptor<RedisScript<?>> scriptCaptor =
                ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(), argsCaptor.capture());

        String script = ((org.springframework.data.redis.core.script.DefaultRedisScript<?>) scriptCaptor.getValue())
                .getScriptAsString();
        assertThat(script)
                .contains("INCR")
                .contains("EXPIRE")
                .contains("if current == 1");
        assertThat(keysCaptor.getValue()).containsExactly("rate_limit:7:/payments:v1");
        assertThat(argsCaptor.getAllValues()).containsExactly("60");
    }

    @Test
    void applicationEvaluationUsesAnApplicationScopedCounterKey() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("1");

        service.evaluateForApplication(12, "/payments", "v1");

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), any(Object[].class));
        assertThat(keysCaptor.getValue()).containsExactly("rate_limit:app:12:/payments:v1");
    }

    @Test
    void applicationEvaluationBeyondTheLimitIsDenied() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("3");
        when(redisTemplate.getExpire(eq("rate_limit:app:12:/payments:v1"), eq(TimeUnit.SECONDS)))
                .thenReturn(20L);

        assertThat(service.evaluateForApplication(12, "/payments", "v1").state()).isEqualTo(State.DENIED);
    }
}