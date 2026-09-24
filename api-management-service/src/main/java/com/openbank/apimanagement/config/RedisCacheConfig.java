package com.openbank.apimanagement.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Redis-backed read-through caching for selected non-sensitive API Management
 * read models (API catalog by id, API version by api id + version id,
 * subscription tier by id). This is a performance optimization only:
 * PostgreSQL remains the source of truth, cache keys contain only database
 * identifiers (never user identity or secrets), and every cache failure is
 * swallowed so a Redis outage degrades to a plain PostgreSQL read instead of
 * an error.
 */
@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheConfig.class);

    @Bean
    RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(
            @Value("${spring.cache.redis.time-to-live:60s}") Duration timeToLive) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(timeToLive)
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(cacheMapper())));
        return builder -> builder.cacheDefaults(defaults);
    }

    /**
     * Values are cached as JSON documents (auditable, not opaque binary) with
     * an explicit type hint so records and entities deserialize back to their
     * concrete types. Field access lets JPA entities (which expose getters but
     * no setters) round-trip. Only the platform read models are ever written
     * here; no secret or per-user material can reach these values because the
     * cached methods only return safe read-model/domain data.
     *
     * {@link ObjectMapper.DefaultTyping#EVERYTHING} is used instead of the
     * narrower {@link ObjectMapper.DefaultTyping#NON_FINAL} because the cached
     * root types are Java records ({@code ApiResponse}, {@code ApiVersionResponse}),
     * which are final classes. {@code NON_FINAL} omits the type hint for final
     * classes, so a cache hit would deserialize into a {@code LinkedHashMap}
     * and fail the invocation's return-type check. The security boundary is
     * {@link #cacheTypeValidator()}, which is the only package that can appear
     * in a cached {@code @class} hint; all other types are rejected at
     * deserialization time and the lookup degrades to PostgreSQL.
     */
    private static ObjectMapper cacheMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new ParameterNamesModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .activateDefaultTyping(cacheTypeValidator(), ObjectMapper.DefaultTyping.EVERYTHING,
                        JsonTypeInfo.As.PROPERTY);
    }

    /**
     * Allow-list of packages that may appear in a cached {@code @class} hint.
     * The three cached value types serialize exactly: {@code ApiResponse} and
     * {@code ApiVersionResponse} (records) and {@code SubscriptionTier} (JPA
     * entity) from {@code com.openbank.apimanagement}; their headers and
     * timestamps are {@code Long} / {@code String} (java.lang) and
     * {@code Instant} (java.time); the lifecycle status is the enum
     * {@code ApiVersionLifecycle} (com.openbank.apimanagement). No other type
     * is reachable, so the list stays minimal. If a future cached model needed
     * a type outside it, deserialization would fail as a miss and PostgreSQL
     * would answer - fail-open by design.
     */
    private static PolymorphicTypeValidator cacheTypeValidator() {
        return BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.openbank.apimanagement")
                .allowIfSubType("java.time")
                .allowIfSubType("java.lang")
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Redis cache lookup failed for cache '{}' key '{}'; reading PostgreSQL instead",
                        cache.getName(), key, exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Redis cache write failed for cache '{}' key '{}'; PostgreSQL remains authoritative",
                        cache.getName(), key, exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Redis cache eviction failed for cache '{}' key '{}'; stale data expires via TTL",
                        cache.getName(), key, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Redis cache clear failed for cache '{}'; stale data expires via TTL",
                        cache.getName(), exception);
            }
        };
    }
}