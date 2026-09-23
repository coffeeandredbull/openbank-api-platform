package com.openbank.apimanagement;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class SubscriptionCheckIntegrationTest {

    private static final String TEST_JWT_SECRET = "integration-test-jwt-secret-value-0123456789-ab";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM subscriptions");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM api_versions");
        jdbcTemplate.execute("DELETE FROM apis");
        jdbcTemplate.execute("DELETE FROM subscription_tiers");
    }

    @Test
    void checkReturnsTrueWhenAuthenticatedUserIsSubscribedToTheApiVersion() throws Exception {
        Long versionId = createApiWithVersion("/payments", "v1");
        createSubscription("42", "DEVELOPER", versionId);

        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true));
    }

    @Test
    void checkReturnsFalseForAnAuthenticatedUserWithoutASubscription() throws Exception {
        createApiWithVersion("/payments", "v1");

        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void checkIgnoresTheApiVersionLifecycleStateIncludingRetired() throws Exception {
        Long versionId = createApiWithVersion("/payments", "v1");
        createSubscription("42", "DEVELOPER", versionId);
        jdbcTemplate.update("UPDATE api_versions SET lifecycle = ? WHERE id = ?", "RETIRED", versionId);

        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true));
    }

    @Test
    void checkIgnoresASubscriptionBelongingToAnotherUsersApplication() throws Exception {
        Long versionId = createApiWithVersion("/payments", "v1");
        createSubscription("77", "DEVELOPER", versionId);

        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void checkReturnsFalseForADifferentApiVersion() throws Exception {
        Long apiId = createApi("/payments");
        createVersion(apiId, "v1");
        Long versionTwo = createVersion(apiId, "v2");
        createSubscription("42", "DEVELOPER", versionTwo);

        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void checkReturnsFalseForAnUnknownApi() throws Exception {
        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/no-such-api")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void checkReturnsFalseForAnUnknownVersion() throws Exception {
        createApiWithVersion("/payments", "v1");

        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v99")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void adminWithoutASubscriptionIsTreatedTheSameAsADeveloper() throws Exception {
        createApiWithVersion("/payments", "v1");

        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void checkIgnoresClientSuppliedUserIdQueryParameter() throws Exception {
        Long versionId = createApiWithVersion("/payments", "v1");
        createSubscription("42", "DEVELOPER", versionId);

        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .param("userId", "999")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true));
    }

    @Test
    void unauthenticatedCheckReturns401() throws Exception {
        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", -3600)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void missingVersionParameterReturns400WithSafeBody() throws Exception {
        mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.version").value("version is required"));
    }

    @Test
    void missingContextPathParameterReturns400WithSafeBody() throws Exception {
        mockMvc.perform(get("/internal/subscription-check")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.contextPath").value("contextPath is required"));
    }

    @Test
    void checkResponseDoesNotExposeSubscriptionInternals() throws Exception {
        Long versionId = createApiWithVersion("/payments", "v1");
        createSubscription("42", "DEVELOPER", versionId);

        String body = mockMvc.perform(get("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v9")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("ownerUserId")
                .doesNotContain("userId")
                .doesNotContain("applicationId")
                .doesNotContain("apiVersionId")
                .doesNotContain("subscription");
    }

    @Test
    void postIsNotSupportedOnTheCheckEndpoint() throws Exception {
        mockMvc.perform(post("/internal/subscription-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    private Long createApiWithVersion(String contextPath, String version) throws Exception {
        Long apiId = createApi(contextPath);
        return createVersion(apiId, version);
    }

    private Long createApi(String contextPath) throws Exception {
        String body = mockMvc.perform(post("/apis")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Payments API",
                                  "description": "Payment operations",
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

    private void createSubscription(String userId, String role, Long apiVersionId) throws Exception {
        String appBody = mockMvc.perform(post("/applications")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s's App",
                                  "description": "Integration test application"
                                }
                                """.formatted(userId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long applicationId = objectMapper.readTree(appBody).get("id").asLong();

        if (apiVersionId != null) {
            mockMvc.perform(post("/subscriptions")
                            .header("Authorization", "Bearer " + token(userId, role, 3600))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "applicationId": %d,
                                      "apiVersionId": %d,
                                      "tierId": %d
                                    }
                                    """.formatted(applicationId, apiVersionId, createTier())))
                    .andExpect(status().isCreated());
        }
    }

    private Long createTier() {
        String name = "tier-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO subscription_tiers (name, description, created_at, updated_at) "
                        + "VALUES (?, ?, now(), now())",
                name, "Check test tier");
        return jdbcTemplate.queryForObject(
                "select id from subscription_tiers where name = ?", Long.class, name);
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