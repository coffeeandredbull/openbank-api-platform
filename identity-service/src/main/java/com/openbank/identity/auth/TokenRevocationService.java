package com.openbank.identity.auth;

import com.openbank.identity.exception.RevocationStorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private static final String KEY_PREFIX = "token_revocation:jti:";
    private static final String REVOKED_MARKER = "1";

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public TokenRevocationService(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    public void revoke(String jti, Instant expiresAt) {
        long ttlSeconds = expiresAt.getEpochSecond() - clock.instant().getEpochSecond();
        if (ttlSeconds <= 0) {
            log.info("identity token revocation skipped because the token is already expired or expiring within the second");
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, REVOKED_MARKER, Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException ex) {
            log.error("identity failed to persist a token revocation in the revocation store", ex);
            throw new RevocationStorageUnavailableException();
        }
    }
}