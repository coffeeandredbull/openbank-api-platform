package com.openbank.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class RedisRateLimitService implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitService.class);

    private static final String INCR_AND_EXPIRE_ONCE_LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return tostring(current)""";

    private static final DefaultRedisScript<String> INCR_AND_EXPIRE_ONCE =
            new DefaultRedisScript<>(INCR_AND_EXPIRE_ONCE_LUA, String.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final RateLimitKeyGenerator keyGenerator;

    public RedisRateLimitService(StringRedisTemplate redisTemplate,
            RateLimitProperties properties,
            RateLimitKeyGenerator keyGenerator) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.keyGenerator = keyGenerator;
    }

    @Override
    public Decision evaluate(long userId, String contextPath, String version) {
        String key = keyGenerator.keyFor(userId, contextPath, version);
        try {
            String currentValue = redisTemplate.execute(
                    INCR_AND_EXPIRE_ONCE,
                    List.of(key),
                    String.valueOf(properties.windowSeconds()));
            if (currentValue == null) {
                return Decision.unavailable();
            }
            long current = Long.parseLong(currentValue);
            if (current <= properties.requests()) {
                return Decision.allowed();
            }
            Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            long retryAfter = ttlSeconds != null && ttlSeconds > 0
                    ? ttlSeconds
                    : properties.windowSeconds();
            return Decision.denied(retryAfter);
        } catch (RuntimeException ex) {
            log.warn("gateway rate limiter unavailable for key={}", key);
            return Decision.unavailable();
        }
    }
}