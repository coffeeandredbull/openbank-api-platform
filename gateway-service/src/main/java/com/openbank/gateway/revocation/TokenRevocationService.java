package com.openbank.gateway.revocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private static final String KEY_PREFIX = "token_revocation:jti:";

    private final StringRedisTemplate redisTemplate;

    public TokenRevocationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public CheckStatus check(String jti) {
        try {
            Boolean revoked = redisTemplate.hasKey(KEY_PREFIX + jti);
            return Boolean.TRUE.equals(revoked) ? CheckStatus.REVOKED : CheckStatus.ACTIVE;
        } catch (RuntimeException ex) {
            log.warn("gateway token revocation check unavailable; failing closed");
            return CheckStatus.UNAVAILABLE;
        }
    }

    public enum CheckStatus {
        REVOKED,
        ACTIVE,
        UNAVAILABLE
    }
}