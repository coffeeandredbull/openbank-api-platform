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
import java.util.List;
import java.util.Map;

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
class ApplicationIntegrationTest {

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

    @BeforeEach
    void cleanApplicationsTable() {
        jdbcTemplate.execute("DELETE FROM applications");
    }

    @Test
    void createPersistsOwnerFromJwtSubject() throws Exception {
        mockMvc.perform(post("/applications")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody("My Demo Application", "Application used for API testing")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("My Demo Application"))
                .andExpect(jsonPath("$.description").value("Application used for API testing"))
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("password").doesNotContain("jwt");
                });

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select name, description, owner_user_id from applications order by id asc limit 1");
        assertThat(row.get("name")).isEqualTo("My Demo Application");
        assertThat(row.get("description")).isEqualTo("Application used for API testing");
        assertThat(((Number) row.get("owner_user_id")).longValue()).isEqualTo(42L);
    }

    @Test
    void createIgnoresOwnerUserIdInRequestBody() throws Exception {
        mockMvc.perform(post("/applications")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "My App",
                                  "description": "Demo",
                                  "ownerUserId": 999
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId").value(42));

        assertThat(jdbcTemplate.queryForObject(
                "select owner_user_id from applications order by id asc limit 1", Long.class)).isEqualTo(42L);
    }

    @Test
    void twoDifferentDevelopersCanUseSameApplicationName() throws Exception {
        assertThat(createApplication("42", "DEVELOPER", "My App", null)).isEqualTo(201);
        assertThat(createApplication("77", "DEVELOPER", "My App", null)).isEqualTo(201);

        List<Long> owners = jdbcTemplate.queryForList(
                "select owner_user_id from applications order by id asc", Long.class);
        assertThat(owners).containsExactly(42L, 77L);
    }

    @Test
    void sameDeveloperCanCreateApplicationsWithTheSameName() throws Exception {
        assertThat(createApplication("42", "DEVELOPER", "My App", null)).isEqualTo(201);
        assertThat(createApplication("42", "DEVELOPER", "My App", null)).isEqualTo(201);

        assertThat(jdbcTemplate.queryForObject("select count(*) from applications", Long.class)).isEqualTo(2L);
    }

    @Test
    void listIsScopedToOwner() throws Exception {
        assertThat(createApplication("42", "DEVELOPER", "First", "one")).isEqualTo(201);
        assertThat(createApplication("42", "DEVELOPER", "Second", "two")).isEqualTo(201);
        assertThat(createApplication("77", "DEVELOPER", "Other's App", null)).isEqualTo(201);

        mockMvc.perform(get("/applications")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("First"))
                .andExpect(jsonPath("$[1].name").value("Second"))
                .andExpect(jsonPath("$[0].ownerUserId").value(42))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("Other's App");
                });

        mockMvc.perform(get("/applications")
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Other's App"));
    }

    @Test
    void getIsScopedToOwner() throws Exception {
        assertThat(createApplication("42", "DEVELOPER", "Secret App", "mine only")).isEqualTo(201);
        Long applicationId = jdbcTemplate.queryForObject(
                "select id from applications order by id asc limit 1", Long.class);

        mockMvc.perform(get("/applications/" + applicationId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(applicationId))
                .andExpect(jsonPath("$.name").value("Secret App"));

        mockMvc.perform(get("/applications/" + applicationId)
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("Secret App");
                });
    }

    @Test
    void updateIsScopedToOwner() throws Exception {
        assertThat(createApplication("42", "DEVELOPER", "Original Name", "Original description")).isEqualTo(201);
        Long applicationId = jdbcTemplate.queryForObject(
                "select id from applications order by id asc limit 1", Long.class);
        String createdAtBefore = jdbcTemplate.queryForObject(
                "select created_at::text from applications where id = ?", String.class, applicationId);
        String updatedAtBefore = jdbcTemplate.queryForObject(
                "select updated_at::text from applications where id = ?", String.class, applicationId);

        mockMvc.perform(patch("/applications/" + applicationId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Updated Name",
                                  "description": "Updated description"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.description").value("Updated description"))
                .andExpect(jsonPath("$.ownerUserId").value(42));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select name, description, owner_user_id, created_at::text as created_at, updated_at::text as updated_at "
                        + "from applications where id = ?", applicationId);
        assertThat(row.get("name")).isEqualTo("Updated Name");
        assertThat(row.get("description")).isEqualTo("Updated description");
        assertThat(((Number) row.get("owner_user_id")).longValue()).isEqualTo(42L);
        assertThat(row.get("created_at")).isEqualTo(createdAtBefore);
        assertThat(row.get("updated_at")).isNotEqualTo(updatedAtBefore);

        mockMvc.perform(patch("/applications/" + applicationId)
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Hijacked"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject(
                "select name from applications where id = ?", String.class, applicationId)).isEqualTo("Updated Name");
    }

    @Test
    void updateWithEmptyBodyReturnsUnchangedState() throws Exception {
        assertThat(createApplication("42", "DEVELOPER", "My App", "Demo")).isEqualTo(201);
        Long applicationId = jdbcTemplate.queryForObject(
                "select id from applications order by id asc limit 1", Long.class);
        String updatedAtBefore = jdbcTemplate.queryForObject(
                "select updated_at::text from applications where id = ?", String.class, applicationId);

        mockMvc.perform(patch("/applications/" + applicationId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My App"));

        assertThat(jdbcTemplate.queryForObject(
                "select updated_at::text from applications where id = ?", String.class, applicationId))
                .isEqualTo(updatedAtBefore);
    }

    @Test
    void createWithBlankNameReturns400() throws Exception {
        mockMvc.perform(post("/applications")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody("   ", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .containsAnyOf(
                                    "name is required",
                                    "name must not contain leading or trailing whitespace")
                            .doesNotContain("Exception");
                });

        assertThat(jdbcTemplate.queryForObject("select count(*) from applications", Long.class)).isEqualTo(0L);
    }

    @Test
    void updateWithBlankNameReturns400() throws Exception {
        assertThat(createApplication("42", "DEVELOPER", "My App", null)).isEqualTo(201);
        Long applicationId = jdbcTemplate.queryForObject(
                "select id from applications order by id asc limit 1", Long.class);

        mockMvc.perform(patch("/applications/" + applicationId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "  "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(jdbcTemplate.queryForObject(
                "select name from applications where id = ?", String.class, applicationId)).isEqualTo("My App");
    }

    @Test
    void getNonexistentApplicationReturns404() throws Exception {
        mockMvc.perform(get("/applications/98765")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Application with id 98765 does not exist"));
    }

    @Test
    void unauthenticatedListReturns401() throws Exception {
        mockMvc.perform(get("/applications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain(TEST_JWT_SECRET);
                });
    }

    private int createApplication(String userId, String role, String name, String description) throws Exception {
        return mockMvc.perform(post("/applications")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationBody(name, description)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String applicationBody(String name, String description) {
        if (description == null) {
            return """
                    {
                      "name": "%s"
                    }
                    """.formatted(name);
        }
        return """
                {
                  "name": "%s",
                  "description": "%s"
                }
                """.formatted(name, description);
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