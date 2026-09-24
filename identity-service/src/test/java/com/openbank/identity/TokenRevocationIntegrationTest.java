package com.openbank.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab",
        "spring.data.redis.password=" + TokenRevocationIntegrationTest.TEST_REDIS_PASSWORD
})
class TokenRevocationIntegrationTest {

    static final String TEST_REDIS_PASSWORD = "integration-test-redis-password";

    private static final String TEST_JWT_SECRET = "integration-test-jwt-secret-value-0123456789-ab";
    private static final AtomicLong EMAIL_SEQUENCE = new AtomicLong(0);
    private static final String PASSWORD = "Correct-Horse-42";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no",
                    "--requirepass", TEST_REDIS_PASSWORD);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void clearRevocations() {
        stringRedisTemplate.delete(stringRedisTemplate.keys("token_revocation:jti:*"));
    }

    @Test
    void loginIssuesATokenWithAJtiAndRevokePersistsAnExpiryBoundMarker() throws Exception {
        String token = registerAndLogin(uniqueEmail("revoke"));

        String jti = SignedJWT.parse(token).getJWTClaimsSet().getJWTID();
        assertThat(jti).isNotBlank();
        String key = "token_revocation:jti:" + jti;
        assertThat(stringRedisTemplate.hasKey(key)).isFalse();

        mockMvc.perform(post("/auth/revoke")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));

        assertThat(stringRedisTemplate.hasKey(key)).as("revocation entry created").isTrue();
        assertThat(stringRedisTemplate.opsForValue().get(key))
                .as("only a marker is stored, never the JWT or any secret")
                .isEqualTo("1");
        Long ttlSeconds = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlSeconds).as("entry lives no longer than the token").isBetween(1L, 3600L);
        assertThat(token).doesNotContain("password").doesNotContain("passwordHash");
    }

    @Test
    void eachLoginIssuesADistinctJtiAndOnlyTheRevokedTokenIsMarked() throws Exception {
        String email = uniqueEmail("multiple");
        register(email);

        String first = login(email);
        String second = login(email);

        String firstJti = SignedJWT.parse(first).getJWTClaimsSet().getJWTID();
        String secondJti = SignedJWT.parse(second).getJWTClaimsSet().getJWTID();
        assertThat(secondJti).isNotEqualTo(firstJti);

        mockMvc.perform(post("/auth/revoke").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk());

        assertThat(stringRedisTemplate.hasKey("token_revocation:jti:" + firstJti)).isTrue();
        assertThat(stringRedisTemplate.hasKey("token_revocation:jti:" + secondJti)).isFalse();
    }

    @Test
    void revokeIsIdempotentAndNeverStoresMoreThanAMarker() throws Exception {
        String token = registerAndLogin(uniqueEmail("idempotent"));
        String jti = SignedJWT.parse(token).getJWTClaimsSet().getJWTID();
        String key = "token_revocation:jti:" + jti;

        mockMvc.perform(post("/auth/revoke").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/revoke").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("1");
    }

    @Test
    void revokeWithoutATokenReturns401() throws Exception {
        mockMvc.perform(post("/auth/revoke"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"));
    }

    @Test
    void revokeWithAnInvalidTokenReturns401() throws Exception {
        mockMvc.perform(post("/auth/revoke").header("Authorization", "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/auth/revoke")
                        .header("Authorization", "Bearer " + expiredToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private String registerAndLogin(String email) throws Exception {
        register(email);
        return login(email);
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s",
                                  "role": "DEVELOPER"
                                }
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private String expiredToken() throws Exception {
        java.time.Instant now = java.time.Instant.now();
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .subject("1")
                .claim("role", "DEVELOPER")
                .issueTime(java.util.Date.from(now.minusSeconds(7200)))
                .expirationTime(java.util.Date.from(now.minusSeconds(3600)))
                .build();
        var jwt = new SignedJWT(
                new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.HS256).build(),
                claims);
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(
                TEST_JWT_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + EMAIL_SEQUENCE.incrementAndGet() + "@example.com";
    }
}