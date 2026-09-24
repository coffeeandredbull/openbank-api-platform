package com.openbank.apimanagement;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.apimanagement.credential.CredentialCreatedResponse;
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
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class CredentialCheckIntegrationTest {

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
        jdbcTemplate.execute("DELETE FROM credentials");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM api_versions");
        jdbcTemplate.execute("DELETE FROM apis");
        jdbcTemplate.execute("DELETE FROM subscription_tiers");
    }

    @Test
    void validCredentialReturnsAuthenticatedWithTheApplicationsOwnSubscription() throws Exception {
        Long versionId = createApiWithVersion("/payments", "v1");
        Long applicationId = createApplication("42", "Payments App");
        activateSubscription(createSubscription(applicationId, versionId));
        CredentialCreatedResponse credential = createCredential(applicationId);

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", basicHeader(credential)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(jsonPath("$.subscribed").value(true));
    }

    @Test
    void validCredentialWithAnInactiveSubscriptionReportsSubscribedFalse() throws Exception {
        Long versionId = createApiWithVersion("/payments", "v1");
        Long applicationId = createApplication("42", "Payments App");
        createSubscription(applicationId, versionId);
        CredentialCreatedResponse credential = createCredential(applicationId);

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", basicHeader(credential)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void validCredentialWithoutASubscriptionReportsSubscribedFalse() throws Exception {
        createApiWithVersion("/payments", "v1");
        Long applicationId = createApplication("42", "Payments App");
        CredentialCreatedResponse credential = createCredential(applicationId);

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", basicHeader(credential)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void subscriptionOfAnotherApplicationOwnedByTheSameUserIsNotCounted() throws Exception {
        Long versionId = createApiWithVersion("/payments", "v1");
        Long subscribedAppId = createApplication("42", "Subscribed App");
        createSubscription(subscribedAppId, versionId);
        Long callingAppId = createApplication("42", "Calling App");
        CredentialCreatedResponse credential = createCredential(callingAppId);

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", basicHeader(credential)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.applicationId").value(callingAppId))
                .andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void validCredentialReturnsFalseForAnUnknownApiPath() throws Exception {
        Long applicationId = createApplication("42", "Payments App");
        CredentialCreatedResponse credential = createCredential(applicationId);

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/no-such-api")
                        .param("version", "v1")
                        .header("Authorization", basicHeader(credential)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void wrongClientSecretReturns401WithGenericBody() throws Exception {
        Long applicationId = createApplication("42", "Payments App");
        CredentialCreatedResponse credential = createCredential(applicationId);

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", basicHeader(credential.clientId(), "wrong-secret-value")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.applicationId").doesNotExist())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());
    }

    @Test
    void unknownClientIdReturns401() throws Exception {
        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", basicHeader("no-such-client-id", "whatever")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void missingAuthorizationHeaderReturns401() throws Exception {
        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void malformedBasicHeaderReturns401() throws Exception {
        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Basic !!!not-base64!!!"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerTokenIsNotAcceptedOnTheCredentialCheckEndpoint() throws Exception {
        createApiWithVersion("/payments", "v1");
        createApplication("42", "Payments App");

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void responseNeverExposesTheClientSecretOrItsHash() throws Exception {
        Long versionId = createApiWithVersion("/payments", "v1");
        Long applicationId = createApplication("42", "Payments App");
        activateSubscription(createSubscription(applicationId, versionId));
        CredentialCreatedResponse credential = createCredential(applicationId);

        String body = mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", basicHeader(credential)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(credential.clientSecret())
                .doesNotContain("clientSecret")
                .doesNotContain("clientSecretHash")
                .doesNotContain("credential");
    }

    private Long createApiWithVersion(String contextPath, String version) throws Exception {
        return createVersion(createApi(contextPath), version);
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

    private Long createApplication(String ownerUserId, String name) throws Exception {
        String body = mockMvc.perform(post("/applications")
                        .header("Authorization", "Bearer " + token(ownerUserId, "DEVELOPER", 3600))
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

    private Long createSubscription(Long applicationId, Long apiVersionId) throws Exception {
        String ownerUserId = jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM applications WHERE id = ?", Long.class, applicationId).toString();
        String body = mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token(ownerUserId, "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "apiVersionId": %d,
                                  "tierId": %d
                                }
                                """.formatted(applicationId, apiVersionId, createTier())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void activateSubscription(Long subscriptionId) throws Exception {
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

    private Long createTier() {
        String name = "tier-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO subscription_tiers (name, description, created_at, updated_at) "
                        + "VALUES (?, ?, now(), now())",
                name, "Credential check tier");
        return jdbcTemplate.queryForObject(
                "select id from subscription_tiers where name = ?", Long.class, name);
    }

    private com.openbank.apimanagement.credential.CredentialCreatedResponse createCredential(Long applicationId)
            throws Exception {
        String ownerUserId = jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM applications WHERE id = ?", Long.class, applicationId).toString();
        String body = mockMvc.perform(post("/credentials")
                        .header("Authorization", "Bearer " + token(ownerUserId, "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d
                                }
                                """.formatted(applicationId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readValue(body, CredentialCreatedResponse.class);
    }

    private String basicHeader(CredentialCreatedResponse credential) {
        return basicHeader(credential.clientId(), credential.clientSecret());
    }

    private String basicHeader(String clientId, String clientSecret) {
        String raw = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
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