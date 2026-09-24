package com.openbank.identity.auth;

import com.openbank.identity.exception.RevocationStorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenRevocationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final TokenRevocationService service = new TokenRevocationService(redisTemplate, CLOCK);

    TokenRevocationServiceTest() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void revokeStoresOnlyAMarkerUnderTheJtiKeyForTheTokensRemainingLife() {
        service.revoke("jti-123", NOW.plusSeconds(90));

        verify(redisTemplate).opsForValue();
        verify(valueOperations).set(eq("token_revocation:jti:jti-123"), eq("1"), eq(Duration.ofSeconds(90)));
    }

    @Test
    void revocationTtlNeverExceedsTheTokensRemainingLifetime() {
        service.revoke("jti-123", NOW.plusSeconds(1).plusMillis(900));

        verify(valueOperations).set(eq("token_revocation:jti:jti-123"), eq("1"), eq(Duration.ofSeconds(1)));
    }

    @Test
    void revokeSkipsTokensThatAreAlreadyExpiredOrExpiringWithinTheSecond() {
        service.revoke("jti-123", NOW);
        service.revoke("jti-456", NOW.plusMillis(400));

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void redisFailureThrowsRevocationStorageUnavailable() {
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> service.revoke("jti-123", NOW.plusSeconds(60)))
                .isInstanceOf(RevocationStorageUnavailableException.class)
                .hasMessage("The token revocation store is unavailable");
    }
}