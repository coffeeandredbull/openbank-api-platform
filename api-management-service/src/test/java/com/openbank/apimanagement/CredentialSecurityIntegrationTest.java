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
class CredentialSecurityIntegrationTest {

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
        jdbcTemplate.execute("DELETE FROM credentials");
        jdbcTemplate.execute("DELETE FROM applications");
    }

    @Test
    void developerCanCreateListAndGetOwnCredentials() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", "DEVELOPER", applicationId);

        mockMvc.perform(get("/credentials")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(credentialId))
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist());

        mockMvc.perform(get("/credentials/" + credentialId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId))
                .andExpect(jsonPath("$.clientId").isNotEmpty())
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void adminCanCreateListAndGetOwnCredentials() throws Exception {
        Long applicationId = createApplication("1", "ADMIN", "Admin App");
        Long credentialId = createCredentialAndReadId("1", "ADMIN", applicationId);

        mockMvc.perform(get("/credentials")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/credentials/" + credentialId)
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").isNotEmpty());
    }

    @Test
    void developerCannotAccessAnotherUsersCredential() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", "DEVELOPER", applicationId);

        mockMvc.perform(get("/credentials/" + credentialId)
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
    }

    @Test
    void adminCannotAccessAnotherUsersCredential() throws Exception {
        Long applicationId = createApplication("42", "DEVELOPER", "Anna's App");
        Long credentialId = createCredentialAndReadId("42", "DEVELOPER", applicationId);

        mockMvc.perform(get("/credentials/" + credentialId)
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
    }

    @Test
    void developerCannotCreateCredentialForAnotherUsersApplication() throws Exception {
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
    void adminCannotCreateCredentialForAnotherUsersApplication() throws Exception {
        Long applicationId = createApplication("77", "DEVELOPER", "Bob's App");

        mockMvc.perform(post("/credentials")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
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
    void adminListingDoesNotExposeOtherUsersCredentials() throws Exception {
        Long app42 = createApplication("42", "DEVELOPER", "Anna's App");
        createCredentialAndReadId("42", "DEVELOPER", app42);
        Long app77 = createApplication("77", "DEVELOPER", "Bob's App");
        createCredentialAndReadId("77", "DEVELOPER", app77);

        mockMvc.perform(get("/credentials")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
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
    void invalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/credentials")
                        .header("Authorization", "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        mockMvc.perform(get("/credentials")
                        .header("Authorization", "Bearer " + token("999", "ADMIN", -3600)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tamperedTokenReturns401() throws Exception {
        mockMvc.perform(get("/credentials")
                        .header("Authorization", "Bearer " + tokenWithDifferentSecret("1", "ADMIN")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unknownRoleFailsClosedTo401() throws Exception {
        mockMvc.perform(get("/credentials")
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

    private Long createCredentialAndReadId(String userId, String role, Long applicationId) throws Exception {
        String body = mockMvc.perform(post("/credentials")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
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