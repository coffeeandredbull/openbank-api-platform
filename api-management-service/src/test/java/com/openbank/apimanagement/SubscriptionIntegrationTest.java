package com.openbank.apimanagement;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class SubscriptionIntegrationTest {

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
    void createPersistsSubscriptionWithRelationshipsAndTimestamps() throws Exception {
        Long versionId = createVersionedApi();
        Long tierId = createTier();

        Long applicationId = createApplication("42", "DEVELOPER", "My App");
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
                .andExpect(status().isCreated())
                .andExpect(r -> {
                    JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
                    assertThat(body.get("id").asLong()).isPositive();
                    assertThat(body.get("applicationId").asLong()).isEqualTo(applicationId);
                    assertThat(body.get("apiVersionId").asLong()).isEqualTo(versionId);
                    assertThat(body.get("tierId").asLong()).isEqualTo(tierId);
                    assertThat(body.get("tierName").asText()).isNotBlank();
                    assertThat(body.get("status").asText()).isEqualTo("PENDING");
                    assertThat(body.get("revokedAt").isNull()).isTrue();
                    assertThat(body.get("createdAt").asText()).isEqualTo(body.get("updatedAt").asText());
                });

        List<Long> owners = jdbcTemplate.queryForList(
                "select application_id from subscriptions order by id asc", Long.class);
        assertThat(owners).containsExactly(applicationId);
        Long apiVersionIdStored = jdbcTemplate.queryForObject(
                "select api_version_id from subscriptions order by id asc limit 1", Long.class);
        assertThat(apiVersionIdStored).isEqualTo(versionId);
        Long tierIdStored = jdbcTemplate.queryForObject(
                "select tier_id from subscriptions order by id asc limit 1", Long.class);
        assertThat(tierIdStored).isEqualTo(tierId);
        String statusStored = jdbcTemplate.queryForObject(
                "select status from subscriptions order by id asc limit 1", String.class);
        assertThat(statusStored).isEqualTo("PENDING");
        String revokedAtStored = jdbcTemplate.queryForObject(
                "select revoked_at::text from subscriptions order by id asc limit 1", String.class);
        assertThat(revokedAtStored).isNull();
        String createdAt = jdbcTemplate.queryForObject(
                "select created_at::text from subscriptions order by id asc limit 1", String.class);
        String updatedAt = jdbcTemplate.queryForObject(
                "select updated_at::text from subscriptions order by id asc limit 1", String.class);
        assertThat(createdAt).isEqualTo(updatedAt);
    }

    @Test
    void createRejectsMissingTier() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "My App");

        mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "apiVersionId": %d,
                                  "tierId": 55555
                                }
                                """.formatted(applicationId, versionId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Subscription tier with id 55555 does not exist"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from subscriptions", Long.class)).isZero();
    }

    @Test
    void createRejectsMissingTierIdWith400() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "My App");

        mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "apiVersionId": %d
                                }
                                """.formatted(applicationId, versionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.tierId").value("tierId is required"));
    }

    @Test
    void duplicateSubscriptionReturns409AndPersistsOnlyOneRow() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "My App");

        assertThat(subscribeAs("42", "DEVELOPER", applicationId, versionId)).isEqualTo(201);

        mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "apiVersionId": %d,
                                  "tierId": %d
                                }
                                """.formatted(applicationId, versionId, createTier())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_ALREADY_EXISTS"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("unique constraint")
                            .doesNotContain("SQL")
                            .doesNotContain("DataIntegrityViolation");
                });

        assertThat(jdbcTemplate.queryForObject("select count(*) from subscriptions", Long.class)).isEqualTo(1L);
    }

    @Test
    void twoDifferentApplicationsCanSubscribeToTheSameApiVersion() throws Exception {
        Long versionId = createVersionedApi();
        Long app42 = createApplication("42", "DEVELOPER", "Anna's App");
        Long app77 = createApplication("77", "DEVELOPER", "Bob's App");

        assertThat(subscribeAs("42", "DEVELOPER", app42, versionId)).isEqualTo(201);
        assertThat(subscribeAs("77", "DEVELOPER", app77, versionId)).isEqualTo(201);

        assertThat(jdbcTemplate.queryForObject("select count(*) from subscriptions", Long.class)).isEqualTo(2L);
    }

    @Test
    void getReturnsSubscriptionForOwnedApplication() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "My App");
        Long subscriptionId = subscribeAndReadId("42", "DEVELOPER", applicationId, versionId);

        mockMvc.perform(get("/subscriptions/" + subscriptionId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subscriptionId))
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.apiVersionId").value(versionId))
                .andExpect(jsonPath("$.tierId").isNumber())
                .andExpect(jsonPath("$.tierName").isNotEmpty())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("ownerUserId");
                });
    }

    @Test
    void getReturns404ForSubscriptionOfAnotherUsersApplication() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long subscriptionId = subscribeAndReadId("42", "DEVELOPER", applicationId, versionId);

        mockMvc.perform(get("/subscriptions/" + subscriptionId)
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_SUBSCRIPTION_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/subscriptions/" + subscriptionId))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("Anna's App");
                });
    }

    @Test
    void listReturnsOnlySubscriptionsForOwnedApplicationsOrderedByIdAsc() throws Exception {
        Long apiId = createApi("/payments");
        Long versionOne = createVersion(apiId, "v1");
        Long versionTwo = createVersion(apiId, "v2");
        Long app42 = createApplication("42", "DEVELOPER", "Anna's App");
        Long app77 = createApplication("77", "DEVELOPER", "Bob's App");

        assertThat(subscribeAs("42", "DEVELOPER", app42, versionOne)).isEqualTo(201);
        assertThat(subscribeAs("42", "DEVELOPER", app42, versionTwo)).isEqualTo(201);
        assertThat(subscribeAs("77", "DEVELOPER", app77, versionOne)).isEqualTo(201);

        mockMvc.perform(get("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].apiVersionId").value(versionOne))
                .andExpect(jsonPath("$[1].apiVersionId").value(versionTwo))
                .andExpect(r -> {
                    JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
                    assertThat(body.get(0).get("id").asLong()).isLessThan(body.get(1).get("id").asLong());
                    assertThat(r.getResponse().getContentAsString()).doesNotContain("Bob's App");
                });

        mockMvc.perform(get("/subscriptions")
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].applicationId").value(app77));
    }

    @Test
    void createRejectsApplicationOwnedByAnotherUser() throws Exception {
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
    void createRejectsMissingApplication() throws Exception {
        Long versionId = createVersionedApi();
        Long tierId = createTier();

        mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 98765,
                                  "apiVersionId": %d,
                                  "tierId": %d
                                }
                                """.formatted(versionId, tierId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Application with id 98765 does not exist"));
    }

    @Test
    void createRejectsMissingApiVersion() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "My App");
        Long tierId = createTier();

        mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "apiVersionId": 55555,
                                  "tierId": %d
                                }
                                """.formatted(applicationId, tierId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_VERSION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Api version with id 55555 does not exist"));
    }

    @Test
    void createRejectsMissingApplicationIdWith400() throws Exception {
        Long versionId = createVersionedApi();

        mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "apiVersionId": %d
                                }
                                """.formatted(versionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.applicationId").value("applicationId is required"));
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
    void subscriptionsTableHasExpectedSchemaAndConstraints() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscriptions' "
                        + "order by ordinal_position",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "application_id", "api_version_id", "tier_id", "status", "revoked_at", "created_at", "updated_at");

        Integer nullableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscriptions' "
                        + "and is_nullable = 'YES'",
                Integer.class);
        assertThat(nullableCount).isEqualTo(1);

        String statusType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'subscriptions' "
                        + "and column_name = 'status'",
                String.class);
        assertThat(statusType).isEqualTo("character varying");

        List<String> uniqueColumns = jdbcTemplate.queryForList(
                "select a.attname from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "join pg_attribute a on a.attrelid = con.conrelid and a.attnum = any(con.conkey) "
                        + "where rel.relname = 'subscriptions' and con.contype = 'u' "
                        + "order by a.attnum",
                String.class);
        assertThat(uniqueColumns).containsExactlyInAnyOrder("application_id", "api_version_id");

        List<String> foreignKeyTargets = jdbcTemplate.queryForList(
                "select con.confrelid::regclass::text from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "where rel.relname = 'subscriptions' and con.contype = 'f' "
                        + "order by 1",
                String.class);
        assertThat(foreignKeyTargets).containsExactlyInAnyOrder("applications", "api_versions", "subscription_tiers");
    }

    @Test
    void adminWalksASubscriptionThroughTheFullLifecycleAndRevocationPersists() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "My App");
        Long subscriptionId = subscribeAndReadId("42", "DEVELOPER", applicationId, versionId);

        setStatus(subscriptionId, "ACTIVE", "1", "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(subscriptionId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.revokedAt").value(nullValue()));

        setStatus(subscriptionId, "REVOKED", "1", "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(r -> {
                    JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
                    assertThat(body.get("revokedAt").isNull()).isFalse();
                });

        String statusStored = jdbcTemplate.queryForObject(
                "select status from subscriptions where id = ?", String.class, subscriptionId);
        assertThat(statusStored).isEqualTo("REVOKED");
        String revokedAtStored = jdbcTemplate.queryForObject(
                "select revoked_at::text from subscriptions where id = ?", String.class, subscriptionId);
        assertThat(revokedAtStored).isNotNull();
    }

    @Test
    void adminCanDenyThenActivateASubscription() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "My App");
        Long subscriptionId = subscribeAndReadId("42", "DEVELOPER", applicationId, versionId);

        setStatus(subscriptionId, "DENIED", "1", "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"));

        setStatus(subscriptionId, "ACTIVE", "1", "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void adminCanChangeTheStatusOfAnotherDevelopersSubscription() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("77", "DEVELOPER", "Bob's App");
        Long subscriptionId = subscribeAndReadId("77", "DEVELOPER", applicationId, versionId);

        setStatus(subscriptionId, "ACTIVE", "1", "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        String statusStored = jdbcTemplate.queryForObject(
                "select status from subscriptions where id = ?", String.class, subscriptionId);
        assertThat(statusStored).isEqualTo("ACTIVE");
    }

    @Test
    void invalidStatusTransitionsReturn409() throws Exception {
        Long versionId = createVersionedApi();

        Long pendingSub = createApplication("42", "DEVELOPER", "A");
        Long pending = subscribeAndReadId("42", "DEVELOPER", pendingSub, versionId);
        setStatus(pending, "PENDING", "1", "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SUBSCRIPTION_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message")
                        .value("Subscription status transition from PENDING to PENDING is not allowed"));

        Long activeSub = createApplication("42", "DEVELOPER", "B");
        Long active = subscribeAndReadId("42", "DEVELOPER", activeSub, versionId);
        setStatus(active, "ACTIVE", "1", "ADMIN").andExpect(status().isOk());
        setStatus(active, "PENDING", "1", "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SUBSCRIPTION_STATUS_TRANSITION"));

        Long revokedSub = createApplication("42", "DEVELOPER", "C");
        Long revoked = subscribeAndReadId("42", "DEVELOPER", revokedSub, versionId);
        setStatus(revoked, "REVOKED", "1", "ADMIN").andExpect(status().isOk());
        setStatus(revoked, "ACTIVE", "1", "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SUBSCRIPTION_STATUS_TRANSITION"));

        String statusStored = jdbcTemplate.queryForObject(
                "select status from subscriptions where id = ?", String.class, revoked);
        assertThat(statusStored).isEqualTo("REVOKED");
    }

    @Test
    void developersCannotChangeSubscriptionStatus() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "My App");
        Long subscriptionId = subscribeAndReadId("42", "DEVELOPER", applicationId, versionId);

        setStatus(subscriptionId, "ACTIVE", "42", "DEVELOPER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        String statusStored = jdbcTemplate.queryForObject(
                "select status from subscriptions where id = ?", String.class, subscriptionId);
        assertThat(statusStored).isEqualTo("PENDING");
    }

    @Test
    void unauthenticatedStatusChangeRequestsReturn401() throws Exception {
        mockMvc.perform(patch("/subscriptions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void updateStatusRejectsMissingStatusFieldWith400() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "My App");
        Long subscriptionId = subscribeAndReadId("42", "DEVELOPER", applicationId, versionId);

        mockMvc.perform(patch("/subscriptions/" + subscriptionId + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("status is required"));
    }

    @Test
    void updateStatusRejectsInvalidStatusValueWith400() throws Exception {
        Long versionId = createVersionedApi();
        Long applicationId = createApplication("42", "DEVELOPER", "My App");
        Long subscriptionId = subscribeAndReadId("42", "DEVELOPER", applicationId, versionId);

        mockMvc.perform(patch("/subscriptions/" + subscriptionId + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ARCHIVED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("invalid value"));
    }

    @Test
    void updateStatusReturns404WhenSubscriptionDoesNotExist() throws Exception {
        mockMvc.perform(patch("/subscriptions/98765/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_SUBSCRIPTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Subscription with id 98765 does not exist"));
    }

    private Long createVersionedApi() throws Exception {
        Long apiId = createApi("/payments");
        return createVersion(apiId, "v1");
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

    private Long createTier() {
        String name = "tier-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO subscription_tiers (name, description, created_at, updated_at) "
                        + "VALUES (?, ?, now(), now())",
                name, "Integration test tier");
        return jdbcTemplate.queryForObject(
                "select id from subscription_tiers where name = ?", Long.class, name);
    }

    private int subscribeAs(String userId, String role, Long applicationId, Long apiVersionId) throws Exception {
        return mockMvc.perform(post("/subscriptions")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "apiVersionId": %d,
                                  "tierId": %d
                                }
                                """.formatted(applicationId, apiVersionId, createTier())))
                .andReturn()
                .getResponse()
                .getStatus();
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

    private org.springframework.test.web.servlet.ResultActions setStatus(
            Long subscriptionId, String status, String userId, String role) throws Exception {
        return mockMvc.perform(patch("/subscriptions/" + subscriptionId + "/status")
                .header("Authorization", "Bearer " + token(userId, role, 3600))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "status": "%s"
                        }
                        """.formatted(status)));
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