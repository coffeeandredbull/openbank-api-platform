package com.openbank.apimanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class SubscriptionSecurityIntegrationTest {

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
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM subscriptions");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM api_versions");
        jdbcTemplate.execute("DELETE FROM apis");
        jdbcTemplate.execute("DELETE FROM subscription_tiers");
    }

    @Test
    void developerCanCreateListAndGetOwnSubscriptions() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long subscriptionId = subscribeAndReadId("42", "DEVELOPER", applicationId, versionId);

        mockMvc.perform(get("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(subscriptionId));

        mockMvc.perform(get("/subscriptions/" + subscriptionId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.apiVersionId").value(versionId));
    }

    @Test
    void adminCanCreateListAndGetOwnSubscriptions() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("1", "ADMIN", "Admin App");
        Long subscriptionId = subscribeAndReadId("1", "ADMIN", applicationId, versionId);

        mockMvc.perform(get("/subscriptions")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/subscriptions/" + subscriptionId)
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiVersionId").value(versionId));
    }

    @Test
    void developerCannotSubscribeAnotherDevelopersApplication() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("77", "DEVELOPER", "Bob's App");
        Long tierId = createTier();

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
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("Bob's App");
                });

        assertThat(jdbcTemplate.queryForObject("select count(*) from subscriptions", Long.class)).isEqualTo(0L);
    }

    @Test
    void adminCannotSubscribeAnotherDevelopersApplication() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("77", "DEVELOPER", "Bob's App");
        Long tierId = createTier();

        mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "apiVersionId": %d,
                                  "tierId": %d
                                }
                                """.formatted(applicationId, versionId, tierId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
    }

    @Test
    void developerCannotGetAnotherDevelopersSubscription() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long subscriptionId = subscribeAndReadId("42", "DEVELOPER", applicationId, versionId);

        mockMvc.perform(get("/subscriptions/" + subscriptionId)
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_SUBSCRIPTION_NOT_FOUND"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("Anna's App");
                });
    }

    @Test
    void adminCannotAccessAnotherDevelopersSubscription() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long subscriptionId = subscribeAndReadId("42", "DEVELOPER", applicationId, versionId);

        mockMvc.perform(get("/subscriptions/" + subscriptionId)
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_SUBSCRIPTION_NOT_FOUND"));
    }

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/subscriptions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 1,
                                  "apiVersionId": 2
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/subscriptions")
                        .header("Authorization", "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        mockMvc.perform(get("/subscriptions")
                        .header("Authorization", "Bearer " + token("999", "ADMIN", -3600)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tamperedTokenReturns401() throws Exception {
        mockMvc.perform(get("/subscriptions")
                        .header("Authorization", "Bearer " + tokenWithDifferentSecret("1", "ADMIN")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unknownRoleFailsClosedTo401() throws Exception {
        mockMvc.perform(get("/subscriptions")
                        .header("Authorization", "Bearer " + token("77", "CUSTOMER", 3600)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("at com.openbank")
                            .doesNotContain(TEST_JWT_SECRET)
                            .doesNotContain("CUSTOMER");
                });
    }

    private Long createVersionedApi() throws Exception {
        String apiBody = mockMvc.perform(post("/apis")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Payments API",
                                  "description": "Payment operations",
                                  "contextPath": "/payments"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long apiId = objectMapper.readTree(apiBody).get("id").asLong();

        String versionBody = mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": "v1"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(versionBody).get("id").asLong();
    }

    private Long createApplication(String userId, String role, String name) throws Exception {
        String body = mockMvc.perform(post("/applications")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "description": "Security test application"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private Long createTier() {
        String name = "tier-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO subscription_tiers (name, description, created_at, updated_at) "
                        + "VALUES (?, ?, now(), now())",
                name, "Security test tier");
        return jdbcTemplate.queryForObject(
                "select id from subscription_tiers where name = ?", Long.class, name);
    }

    private Long subscribeAndReadId(String userId, String role, Long applicationId, Long apiVersionId) throws Exception {
        String body = mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
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

    private String tokenWithDifferentSecret(String subject, String role) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                claims);
        jwt.sign(new MACSigner("a-completely-different-secret-value-123456789".getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}