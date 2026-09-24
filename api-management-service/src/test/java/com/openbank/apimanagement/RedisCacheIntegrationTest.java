package com.openbank.apimanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.apimanagement.api.ApiService;
import com.openbank.apimanagement.api.ApiVersionLifecycle;
import com.openbank.apimanagement.api.ApiVersionService;
import com.openbank.apimanagement.api.CreateApiRequest;
import com.openbank.apimanagement.api.UpdateApiVersionLifecycleRequest;
import com.openbank.apimanagement.subscription.SubscriptionTier;
import com.openbank.apimanagement.subscription.SubscriptionTierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab",
        "spring.data.redis.password=" + RedisCacheIntegrationTest.TEST_REDIS_PASSWORD
})
@Testcontainers
class RedisCacheIntegrationTest {

    static final String TEST_JWT_SECRET = "integration-test-jwt-secret-value-0123456789-ab";
    static final String TEST_REDIS_PASSWORD = "integration-test-redis-password";
    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final Pattern CACHE_KEY_PATTERN =
            Pattern.compile("(apiCatalog|apiVersion|subscriptionTier)::\\d+(::\\d+)?");

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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ApiService apiService;

    @Autowired
    private ApiVersionService apiVersionService;

    @Autowired
    private SubscriptionTierService subscriptionTierService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanState() {
        stringRedisTemplate.execute((RedisConnection connection) -> {
            connection.serverCommands().flushAll();
            return connection.ping();
        });
        jdbcTemplate.execute("DELETE FROM subscriptions");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM api_versions");
        jdbcTemplate.execute("DELETE FROM apis");
        jdbcTemplate.execute("DELETE FROM subscription_tiers");
    }

    @Test
    void apiCatalogReadHitIsServedFromRedisAndCreateDoesNotEvictExistingEntries() throws Exception {
        Long apiId = createApi("/cache-api-" + SEQUENCE.incrementAndGet());

        String original = getAndReadApi(apiId);
        assertThat(original).contains("Cache API");

        String key = "apiCatalog::" + apiId;
        assertThat(stringRedisTemplate.hasKey(key)).isTrue();
        String value = stringRedisTemplate.opsForValue().get(key);
        assertThat(value).contains("\"@class\":\"com.openbank.apimanagement.api.ApiResponse\"");

        jdbcTemplate.update("UPDATE apis SET name = 'Mutated Out Of Band', updated_at = now() WHERE id = ?", apiId);

        String servedFromCache = getAndReadApi(apiId);
        assertThat(servedFromCache).isEqualTo(original);
        assertThat(servedFromCache).doesNotContain("Mutated Out Of Band");

        Long newApi = createApi("/cache-api-" + SEQUENCE.incrementAndGet());

        assertThat(stringRedisTemplate.hasKey(key))
                .as("a create must not evict still-valid per-id apiCatalog entries")
                .isTrue();
        assertThat(stringRedisTemplate.hasKey("apiCatalog::" + newApi))
                .as("the new id is a fresh miss; it is only cached once read")
                .isFalse();
    }

    @Test
    void apiVersionCreateDoesNotEvictOtherCachedVersions() throws Exception {
        Long apiId = createApi("/cache-version-create-" + SEQUENCE.incrementAndGet());
        Long v1 = createVersion(apiId, "v1");
        getVersion(apiId, v1);
        String v1Key = "apiVersion::" + apiId + "::" + v1;
        assertThat(stringRedisTemplate.hasKey(v1Key)).isTrue();

        Long v2 = createVersion(apiId, "v2");

        assertThat(stringRedisTemplate.hasKey(v1Key))
                .as("creating a new version must not evict cached versions of the same api")
                .isTrue();
        assertThat(stringRedisTemplate.hasKey("apiVersion::" + apiId + "::" + v2))
                .as("the new version id is a fresh miss until read")
                .isFalse();
    }

    @Test
    void subscriptionTierCreateDoesNotEvictExistingCachedTiers() {
        SubscriptionTier first = subscriptionTierService.create(
                "cache-tier-" + SEQUENCE.incrementAndGet(), "first tier", 100, 60);
        SubscriptionTier readBack = subscriptionTierService.get(first.getId());
        assertThat(readBack.getName()).isEqualTo(first.getName());
        String key = "subscriptionTier::" + first.getId();
        assertThat(stringRedisTemplate.hasKey(key)).isTrue();

        subscriptionTierService.create("cache-tier-" + SEQUENCE.incrementAndGet(), "second tier", 200, 60);

        assertThat(stringRedisTemplate.hasKey(key))
                .as("tier creation must not evict other cached tiers (per-id keying)")
                .isTrue();
    }

    @Test
    void apiVersionReadUsesCompositeKeyAndLifecycleChangeEvictsOnlyThatVersion() throws Exception {
        Long apiId = createApi("/cache-version-" + SEQUENCE.incrementAndGet());
        Long versionId = createVersion(apiId, "v1");
        Long otherApiId = createApi("/cache-version-" + SEQUENCE.incrementAndGet());
        Long otherVersionId = createVersion(otherApiId, "v1");

        String body = getVersion(apiId, versionId);
        assertThat(body).contains("v1");
        getVersion(otherApiId, otherVersionId);

        String key = "apiVersion::" + apiId + "::" + versionId;
        String otherKey = "apiVersion::" + otherApiId + "::" + otherVersionId;
        assertThat(stringRedisTemplate.hasKey(key)).isTrue();
        assertThat(stringRedisTemplate.hasKey(otherKey)).isTrue();

        mockMvc.perform(patch("/apis/" + apiId + "/versions/" + versionId + "/lifecycle")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lifecycle": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("PUBLISHED"));

        assertThat(stringRedisTemplate.hasKey(key)).as("lifecycle change must evict the cached version")
                .isFalse();
        assertThat(stringRedisTemplate.hasKey(otherKey)).as("lifecycle change must not evict other versions")
                .isTrue();

        String refreshed = getVersion(apiId, versionId);
        assertThat(refreshed).contains("PUBLISHED");
        assertThat(stringRedisTemplate.hasKey(key)).isTrue();
    }

    @Test
    void rolledBackLifecycleChangeDoesNotEvictTheCachedVersion() throws Exception {
        Long apiId = createApi("/cache-lifecycle-rollback-" + SEQUENCE.incrementAndGet());
        Long versionId = createVersion(apiId, "v1");
        getVersion(apiId, versionId);
        String key = "apiVersion::" + apiId + "::" + versionId;
        assertThat(stringRedisTemplate.hasKey(key)).isTrue();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            apiVersionService.changeLifecycle(apiId, versionId,
                    new UpdateApiVersionLifecycleRequest(ApiVersionLifecycle.PUBLISHED));
            throw new IllegalStateException("force rollback after a successful lifecycle change");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(stringRedisTemplate.hasKey(key))
                .as("a rolled-back lifecycle change must not evict the still-valid cached version")
                .isTrue();
        String cachedAfterRollback = stringRedisTemplate.opsForValue().get(key);
        assertThat(cachedAfterRollback).contains("CREATED");

        String dbLifecycle = jdbcTemplate.queryForObject(
                "SELECT lifecycle FROM api_versions WHERE id = ?", String.class, versionId);
        assertThat(dbLifecycle).isEqualTo("CREATED");
    }

    @Test
    void subscriptionTierReadIsCachedAndStaysUsableWhenRehydratedFromRedis() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "Cache App");
        String tierName = "cache-tier-" + SEQUENCE.incrementAndGet();
        Long tierId = createTier(tierName);

        subscribe(versionId, applicationId, tierId);

        String key = "subscriptionTier::" + tierId;
        assertThat(stringRedisTemplate.hasKey(key)).isTrue();
        String value = stringRedisTemplate.opsForValue().get(key);
        assertThat(value).contains("\"@class\":\"com.openbank.apimanagement.subscription.SubscriptionTier\"");
        assertThat(value).contains(tierName);
        assertThat(value.toLowerCase()).doesNotContain("secret", "password");

        Long secondApplicationId = createApplication("42", "DEVELOPER", "Cache App 2");
        mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "apiVersionId": %d,
                                  "tierId": %d
                                }
                                """.formatted(secondApplicationId, versionId, tierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tierId").value(tierId))
                .andExpect(jsonPath("$.tierName").value(tierName));
    }

    @Test
    void rolledBackCreateDoesNotEvictExistingCatalogEntries() throws Exception {
        Long apiId = createApi("/rollback-probe-" + SEQUENCE.incrementAndGet());
        getApi(apiId);
        String key = "apiCatalog::" + apiId;
        assertThat(stringRedisTemplate.hasKey(key)).isTrue();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            apiService.create(new CreateApiRequest(
                    "Rollback Probe API",
                    "Create succeeds but the surrounding transaction is forced to roll back",
                    "/rollback-create-" + SEQUENCE.incrementAndGet()));
            throw new IllegalStateException("force rollback after a successful create");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(stringRedisTemplate.hasKey(key))
                .as("a rolled-back create must not evict still-valid catalog cache entries")
                .isTrue();
    }

    @Test
    void authorizationChecksAreNotCached() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "Cache App");
        Long tierId = createTier("cache-tier-" + SEQUENCE.incrementAndGet());
        Long subscriptionId = subscribeAndReadId(versionId, applicationId, tierId);
        activate(subscriptionId);

        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true));
        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true));

        Set<String> keys = stringRedisTemplate.keys("*");
        assertThat(keys).as("subscription checks must not write to Redis")
                .containsExactly("subscriptionTier::" + tierId);
    }

    @Test
    void cacheKeysContainOnlyCacheNamesAndDatabaseIdentifiers() throws Exception {
        Long apiId = createApi("/cache-secrets-" + SEQUENCE.incrementAndGet());
        Long versionId = createVersion(apiId, "v1");
        getApi(apiId);
        getVersion(apiId, versionId);
        Long tierId = createTier("cache-tier-" + SEQUENCE.incrementAndGet());
        Long applicationId = createApplication("42", "DEVELOPER", "Cache App");
        subscribe(versionId, applicationId, tierId);

        Set<String> keys = stringRedisTemplate.keys("*");
        assertThat(keys).isNotEmpty();
        for (String key : keys) {
            assertThat(key).as("cache keys must stay structurally safe").matches(CACHE_KEY_PATTERN);
            String lowered = key.toLowerCase();
            assertThat(lowered).doesNotContain("secret", "password", "token", "42");
        }
    }

    @Test
    void allCacheNamesExpireAfterTheDefaultTtlOfSixtySeconds() throws Exception {
        Long apiId = createApi("/cache-ttl-" + SEQUENCE.incrementAndGet());
        Long versionId = createVersion(apiId, "v1");
        getApi(apiId);
        getVersion(apiId, versionId);
        Long tierId = createTier("cache-tier-" + SEQUENCE.incrementAndGet());
        Long applicationId = createApplication("42", "DEVELOPER", "Cache App");
        subscribe(versionId, applicationId, tierId);

        Long catalogTtl = stringRedisTemplate.getExpire("apiCatalog::" + apiId, TimeUnit.SECONDS);
        Long versionTtl = stringRedisTemplate.getExpire("apiVersion::" + apiId + "::" + versionId, TimeUnit.SECONDS);
        Long tierTtl = stringRedisTemplate.getExpire("subscriptionTier::" + tierId, TimeUnit.SECONDS);
        assertThat(catalogTtl).as("apiCatalog default TTL").isBetween(58L, 60L);
        assertThat(versionTtl).as("apiVersion default TTL").isBetween(58L, 60L);
        assertThat(tierTtl).as("subscriptionTier default TTL").isBetween(58L, 60L);
    }

    @Test
    void cachedPayloadsNeverExposeSecretsOrUserIdentity() throws Exception {
        Long apiId = createApi("/cache-payload-" + SEQUENCE.incrementAndGet());
        Long versionId = createVersion(apiId, "v1");
        getApi(apiId);
        getVersion(apiId, versionId);
        Long tierId = createTier("cache-tier-" + SEQUENCE.incrementAndGet());
        Long applicationId = createApplication("42", "DEVELOPER", "Cache App");
        subscribe(versionId, applicationId, tierId);

        Set<String> keys = stringRedisTemplate.keys("*");
        for (String key : keys) {
            String value = stringRedisTemplate.opsForValue().get(key);
            assertThat(value).doesNotContain(TEST_JWT_SECRET);
            String lower = value.toLowerCase();
            assertThat(lower)
                    .doesNotContain("password", "clientsecret", "authorization header", "bearer ")
                    .doesNotContain("sub\":\"42");
        }
    }

    private String getApi(Long id) throws Exception {
        return mockMvc.perform(get("/apis/" + id)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String getAndReadApi(Long id) throws Exception {
        return getApi(id);
    }

    private String getVersion(Long apiId, Long versionId) throws Exception {
        return mockMvc.perform(get("/apis/" + apiId + "/versions/" + versionId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private Long createApi(String contextPath) throws Exception {
        String body = mockMvc.perform(post("/apis")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Cache API",
                                  "description": "API for redis cache coverage",
                                  "contextPath": "%s"
                                }
                                """.formatted(contextPath)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private Long createVersion(Long apiId, String version) throws Exception {
        String body = mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": "%s"
                                }
                                """.formatted(version)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private Long createVersionedApi() throws Exception {
        Long apiId = createApi("/payments");
        return createVersion(apiId, "v1");
    }

    private Long createApplication(String userId, String role, String name) throws Exception {
        String body = mockMvc.perform(post("/applications")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "description": "Integration test application"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private Long createTier(String name) {
        jdbcTemplate.update(
                "INSERT INTO subscription_tiers "
                        + "(name, description, requests_per_window, window_seconds, created_at, updated_at) "
                        + "VALUES (?, ?, 100, 60, now(), now())",
                name, "Integration test tier");
        return jdbcTemplate.queryForObject(
                "select id from subscription_tiers where name = ?", Long.class, name);
    }

    private Long subscribeAndReadId(Long versionId, Long applicationId, Long tierId) throws Exception {
        String body = mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "apiVersionId": %d,
                                  "tierId": %d
                                }
                                """.formatted(applicationId, versionId, tierId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void subscribe(Long versionId, Long applicationId, Long tierId) throws Exception {
        mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "apiVersionId": %d,
                                  "tierId": %d
                                }
                                """.formatted(applicationId, versionId, tierId)))
                .andExpect(status().isCreated());
    }

    private void activate(Long subscriptionId) throws Exception {
        mockMvc.perform(patch("/subscriptions/" + subscriptionId + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk());
    }

    private String token(String subject, String role, long expiresInSeconds) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                claims);
        jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}