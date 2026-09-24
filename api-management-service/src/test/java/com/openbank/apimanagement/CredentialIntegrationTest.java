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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class CredentialIntegrationTest {

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

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM credentials");
        jdbcTemplate.execute("DELETE FROM applications");
    }

    @Test
    void createStoresHashedSecretAndReturnsPlaintextOnce() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "My App");

        String body = mockMvc.perform(post("/credentials")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d
                                }
                                """.formatted(applicationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").isNotEmpty())
                .andExpect(jsonPath("$.clientSecret").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.clientSecretHash").doesNotExist())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Location")).startsWith("/credentials/"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode created = objectMapper.readTree(body);
        String plaintextSecret = created.get("clientSecret").asText();
        String clientId = created.get("clientId").asText();

        String storedHash = jdbcTemplate.queryForObject(
                "select client_secret_hash from credentials where client_id = ?",
                String.class, clientId);
        assertThat(storedHash)
                .isNotNull()
                .startsWith("$2")
                .isNotEqualTo(plaintextSecret);
        assertThat(passwordEncoder.matches(plaintextSecret, storedHash)).isTrue();

        assertThat(jdbcTemplate.queryForObject(
                "select status from credentials where client_id = ?", String.class, clientId))
                .isEqualTo("ACTIVE");

        Integer rowsWithPlaintext = jdbcTemplate.queryForObject(
                "select count(*) from credentials where client_secret_hash = ?",
                Integer.class, plaintextSecret);
        assertThat(rowsWithPlaintext).isZero();

        Integer rowsStoringClientId = jdbcTemplate.queryForObject(
                "select count(*) from credentials where client_id = ?",
                Integer.class, clientId);
        assertThat(rowsStoringClientId).isEqualTo(1);
    }

    @Test
    void createIgnoresClientProvidedClientIdAndSecret() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "My App");

        String body = mockMvc.perform(post("/credentials")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d,
                                  "clientId": "client-supplied-id",
                                  "clientSecret": "client-supplied-secret"
                                }
                                """.formatted(applicationId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode created = objectMapper.readTree(body);
        assertThat(created.get("clientId").asText()).isNotEqualTo("client-supplied-id");
        assertThat(created.get("clientSecret").asText()).isNotEqualTo("client-supplied-secret");
    }

    @Test
    void getReturnsCredentialWithoutSecretOrHash() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "My App");
        Long credentialId = createCredentialAndReadId("42", applicationId);

        mockMvc.perform(get("/credentials/" + credentialId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(credentialId))
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.clientId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.clientSecretHash").doesNotExist())
                .andExpect(r -> {
                    String responseBody = r.getResponse().getContentAsString();
                    assertThat(responseBody).doesNotContain("$2");
                });
    }

    @Test
    void getReturns404ForCrossOwnerCredentialWithoutLeakingClientId() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", applicationId);

        mockMvc.perform(get("/credentials/" + credentialId)
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/credentials/" + credentialId))
                .andExpect(status().isNotFound())
                .andExpect(r -> {
                    String responseBody = r.getResponse().getContentAsString();
                    assertThat(responseBody).doesNotContain("clientSecret");
                });
    }

    @Test
    void listReturnsOwnedCredentialsOnlyOrderedByIdAscWithoutSecrets() throws Exception {
        Long app42 = createApplication("42", "DEVELOPER", "Anna's App");
        createCredentialAndReadId("42", app42);
        Long secondId = createCredentialAndReadId("42", app42);
        Long app77 = createApplication("77", "DEVELOPER", "Bob's App");
        createCredentialAndReadId("77", app77);

        mockMvc.perform(get("/credentials")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].id").value(secondId))
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$[0].clientSecretHash").doesNotExist())
                .andExpect(r -> {
                    String responseBody = r.getResponse().getContentAsString();
                    assertThat(responseBody).doesNotContain("$2");
                });
    }

    @Test
    void createRejectsApplicationOwnedByAnotherUser() throws Exception {
        Long applicationId = createApplication("77", "DEVELOPER", "Bob's App");

        mockMvc.perform(post("/credentials")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": %d
                                }
                                """.formatted(applicationId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from credentials", Long.class)).isZero();
    }

    @Test
    void createRejectsMissingApplication() throws Exception {
        mockMvc.perform(post("/credentials")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 98765
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Application with id 98765 does not exist"));
    }

    @Test
    void createRejectsMissingApplicationIdWith400() throws Exception {
        mockMvc.perform(post("/credentials")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.applicationId").value("applicationId is required"));
    }

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 1
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void credentialsTableHasExpectedSchemaAndConstraints() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'credentials' "
                        + "order by ordinal_position",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "application_id", "client_id", "client_secret_hash", "status", "created_at", "updated_at");

        Integer nullableCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'credentials' "
                        + "and is_nullable = 'YES'",
                Integer.class);
        assertThat(nullableCount).isZero();

        List<String> nonNullableColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'credentials' "
                        + "and is_nullable = 'NO' and column_default is null "
                        + "order by ordinal_position",
                String.class);
        assertThat(nonNullableColumns).contains("status");
        assertThat(jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'credentials' "
                        + "and column_name = 'status'",
                String.class)).isEqualTo("character varying");

        List<String> uniqueConstraintNames = jdbcTemplate.queryForList(
                "select con.conname from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "where rel.relname = 'credentials' and con.contype = 'u' "
                        + "order by con.conname",
                String.class);
        assertThat(uniqueConstraintNames).containsExactly("uc_credential_client_id");

        List<String> uniqueColumns = jdbcTemplate.queryForList(
                "select a.attname from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "join pg_attribute a on a.attrelid = con.conrelid and a.attnum = any(con.conkey) "
                        + "where rel.relname = 'credentials' and con.contype = 'u' "
                        + "order by a.attnum",
                String.class);
        assertThat(uniqueColumns).containsExactly("client_id");

        List<String> foreignKeyTargets = jdbcTemplate.queryForList(
                "select con.confrelid::regclass::text from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "where rel.relname = 'credentials' and con.contype = 'f' "
                        + "order by 1",
                String.class);
        assertThat(foreignKeyTargets).containsExactly("applications");
    }

    @Test
    void statusIsExposedThroughGetAndList() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", applicationId);

        mockMvc.perform(get("/credentials/" + credentialId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/credentials")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$[0].clientSecretHash").doesNotExist());
    }

    @Test
    void adminRevokesAnyCredentialAndStatusPersists() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", applicationId);

        mockMvc.perform(patch("/credentials/" + credentialId + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REVOKED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(credentialId))
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.clientSecretHash").doesNotExist());

        assertThat(jdbcTemplate.queryForObject(
                "select status from credentials where id = ?", String.class, credentialId))
                .isEqualTo("REVOKED");

        mockMvc.perform(get("/credentials/" + credentialId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void reApplyingActiveStatusConflicts() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", applicationId);

        mockMvc.perform(patch("/credentials/" + credentialId + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/credentials/" + credentialId + "/status"))
                .andExpect(jsonPath("$.message")
                        .value("Credential status transition from ACTIVE to ACTIVE is not allowed"));

        assertThat(jdbcTemplate.queryForObject(
                "select status from credentials where id = ?", String.class, credentialId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void revokedCredentialRejectsAllStatusUpdates() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", applicationId);
        String adminToken = "Bearer " + token("1", "ADMIN", 3600);
        String statusPath = "/credentials/" + credentialId + "/status";

        mockMvc.perform(patch(statusPath)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REVOKED"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch(statusPath)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REVOKED"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message")
                        .value("Credential status transition from REVOKED to REVOKED is not allowed"));

        mockMvc.perform(patch(statusPath)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message")
                        .value("Credential status transition from REVOKED to ACTIVE is not allowed"));

        assertThat(jdbcTemplate.queryForObject(
                "select status from credentials where id = ?", String.class, credentialId))
                .isEqualTo("REVOKED");
    }

    @Test
    void invalidStatusValueReturns400() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", applicationId);

        mockMvc.perform(patch("/credentials/" + credentialId + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "SUSPENDED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("invalid value"));
    }

    @Test
    void missingStatusFieldReturns400() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", applicationId);

        mockMvc.perform(patch("/credentials/" + credentialId + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("status is required"));
    }

    @Test
    void statusUpdateForMissingCredentialReturns404() throws Exception {
        mockMvc.perform(patch("/credentials/98765/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REVOKED"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Credential with id 98765 does not exist"));
    }

    @Test
    void developerCannotChangeCredentialStatus() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", applicationId);

        mockMvc.perform(patch("/credentials/" + credentialId + "/status")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REVOKED"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(jdbcTemplate.queryForObject(
                "select status from credentials where id = ?", String.class, credentialId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void unauthenticatedStatusUpdateReturns401() throws Exception {
        mockMvc.perform(patch("/credentials/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REVOKED"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rotationReplacesCredentialInPlaceAndInvalidatesOldCredentials() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        String createBody = mockMvc.perform(post("/credentials")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
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
        JsonNode original = objectMapper.readTree(createBody);
        Long credentialId = original.get("id").asLong();
        String oldClientId = original.get("clientId").asText();
        String oldSecret = original.get("clientSecret").asText();
        String oldHash = jdbcTemplate.queryForObject(
                "select client_secret_hash from credentials where id = ?", String.class, credentialId);

        String rotateBody = mockMvc.perform(post("/credentials/" + credentialId + "/rotate")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(credentialId))
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.clientSecretHash").doesNotExist())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode rotated = objectMapper.readTree(rotateBody);
        String newClientId = rotated.get("clientId").asText();
        String newSecret = rotated.get("clientSecret").asText();

        assertThat(newClientId).isNotEqualTo(oldClientId);
        assertThat(newSecret).isNotEqualTo(oldSecret);
        assertThat(rotated.get("createdAt").asText()).isEqualTo(original.get("createdAt").asText());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from credentials where id = ?", Integer.class, credentialId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select client_id from credentials where id = ?", String.class, credentialId)).isEqualTo(newClientId);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from credentials where client_id = ?", Integer.class, oldClientId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select application_id from credentials where id = ?", Long.class, credentialId)).isEqualTo(applicationId);

        String newHash = jdbcTemplate.queryForObject(
                "select client_secret_hash from credentials where id = ?", String.class, credentialId);
        assertThat(newHash)
                .startsWith("$2")
                .isNotEqualTo(oldHash)
                .isNotEqualTo(newSecret);
        assertThat(passwordEncoder.matches(newSecret, newHash)).isTrue();
        assertThat(passwordEncoder.matches(oldSecret, newHash)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from credentials where client_secret_hash = ?", Integer.class, newSecret)).isZero();

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", basicHeader(oldClientId, oldSecret)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.authenticated").value(false));

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", basicHeader(newClientId, newSecret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(jsonPath("$.subscribed").value(false));

        mockMvc.perform(get("/credentials/" + credentialId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value(newClientId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.clientSecretHash").doesNotExist());
    }

    @Test
    void revokedCredentialCannotRotate() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", applicationId);
        String clientIdBefore = jdbcTemplate.queryForObject(
                "select client_id from credentials where id = ?", String.class, credentialId);
        mockMvc.perform(patch("/credentials/" + credentialId + "/status")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REVOKED"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/credentials/" + credentialId + "/rotate")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message")
                        .value("Credential with id " + credentialId + " is REVOKED and cannot be rotated"));

        assertThat(jdbcTemplate.queryForObject(
                "select client_id from credentials where id = ?", String.class, credentialId))
                .isEqualTo(clientIdBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select status from credentials where id = ?", String.class, credentialId))
                .isEqualTo("REVOKED");
    }

    @Test
    void rotationForMissingCredentialReturns404() throws Exception {
        mockMvc.perform(post("/credentials/98765/rotate")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
    }

    @Test
    void developerCannotRotateCredential() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", applicationId);

        mockMvc.perform(post("/credentials/" + credentialId + "/rotate")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void unauthenticatedRotationReturns401() throws Exception {
        mockMvc.perform(post("/credentials/1/rotate"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void uniqueClientIdConstraintRejectsDuplicateClientId() {
        Long applicationId = insertApplicationRow(42L);
        insertCredentialRow(applicationId, "duplicate-client-id");

        assertThatThrownBy(() -> insertCredentialRow(applicationId, "duplicate-client-id"))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from credentials where client_id = 'duplicate-client-id'", Long.class))
                .isEqualTo(1L);
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

    private Long insertApplicationRow(Long ownerUserId) {
        Long id = jdbcTemplate.queryForObject(
                "insert into applications (name, description, owner_user_id, created_at, updated_at) "
                        + "values ('Raw App', null, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) returning id",
                Long.class, ownerUserId);
        return id;
    }

    private void insertCredentialRow(Long applicationId, String clientId) {
        jdbcTemplate.update(
                "insert into credentials (application_id, client_id, client_secret_hash, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                applicationId, clientId, "$2a$10$manual-insert-hash-value");
    }

    private Long createCredentialAndReadId(String userId, Long applicationId) throws Exception {
        String body = mockMvc.perform(post("/credentials")
                        .header("Authorization", "Bearer " + token(userId, "DEVELOPER", 3600))
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
        return objectMapper.readTree(body).get("id").asLong();
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