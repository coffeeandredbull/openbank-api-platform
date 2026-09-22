package com.openbank.payment;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class AccountIntegrationTest {

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
        jdbcTemplate.execute("DELETE FROM transactions");
        jdbcTemplate.execute("DELETE FROM payments");
        jdbcTemplate.execute("DELETE FROM accounts");
    }

    @Test
    void createPersistsAccountWithOwnerFromJwtAndDefaultCurrency() throws Exception {
        mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(r -> {
                    JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
                    assertThat(body.get("id").asLong()).isPositive();
                    assertThat(body.get("ownerUserId").asLong()).isEqualTo(42L);
                    assertThat(body.get("currency").asText()).isEqualTo("LKR");
                    assertThat(body.get("createdAt").asText()).isEqualTo(body.get("updatedAt").asText());
                });

        Long ownerIdStored = jdbcTemplate.queryForObject(
                "select owner_user_id from accounts order by id asc limit 1", Long.class);
        assertThat(ownerIdStored).isEqualTo(42L);
        String currencyStored = jdbcTemplate.queryForObject(
                "select currency from accounts order by id asc limit 1", String.class);
        assertThat(currencyStored).isEqualTo("LKR");
    }

    @Test
    void createWithSuppliedCurrencyPersistsThatCurrency() throws Exception {
        mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("USD"));

        String currencyStored = jdbcTemplate.queryForObject(
                "select currency from accounts order by id asc limit 1", String.class);
        assertThat(currencyStored).isEqualTo("USD");
    }

    @Test
    void createRejectsInvalidCurrencyWith400() throws Exception {
        mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currency": "eur"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.currency").value("currency must be a valid 3-letter uppercase currency code"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from accounts", Long.class)).isZero();
    }

    @Test
    void createRejectsDuplicateAccountWith409() throws Exception {
        assertThat(createAccount("42", "DEVELOPER")).isEqualTo(201);

        mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("unique constraint")
                            .doesNotContain("SQL")
                            .doesNotContain("DataIntegrityViolation");
                });

        assertThat(jdbcTemplate.queryForObject("select count(*) from accounts", Long.class)).isEqualTo(1L);
    }

    @Test
    void createIgnoresClientSuppliedOwnerUserId() throws Exception {
        mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerUserId": 77
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId").value(42));

        Long ownerIdStored = jdbcTemplate.queryForObject(
                "select owner_user_id from accounts order by id asc limit 1", Long.class);
        assertThat(ownerIdStored).isEqualTo(42L);
    }

    @Test
    void getReturnsAccountForOwnedUser() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");

        mockMvc.perform(get("/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId))
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(jsonPath("$.currency").value("LKR"));
    }

    @Test
    void getReturns404ForAnotherUsersAccount() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");

        mockMvc.perform(get("/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/accounts/" + accountId));
    }

    @Test
    void listReturnsOwnAccountAndEmptyForOthers() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");

        mockMvc.perform(get("/accounts")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(accountId));

        mockMvc.perform(get("/accounts")
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void accountsTableHasExpectedSchemaAndConstraints() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'accounts' "
                        + "order by ordinal_position",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "owner_user_id", "currency", "created_at", "updated_at");

        List<String> nullableColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'accounts' "
                        + "and is_nullable = 'YES'",
                String.class);
        assertThat(nullableColumns).isEmpty();

        List<String> uniqueColumns = jdbcTemplate.queryForList(
                "select a.attname from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "join pg_attribute a on a.attrelid = con.conrelid and a.attnum = any(con.conkey) "
                        + "where rel.relname = 'accounts' and con.contype = 'u' "
                        + "order by a.attnum",
                String.class);
        assertThat(uniqueColumns).containsExactlyInAnyOrder("owner_user_id");

        Integer foreignKeyCount = jdbcTemplate.queryForObject(
                "select count(*) from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "where rel.relname = 'accounts' and con.contype = 'f'",
                Integer.class);
        assertThat(foreignKeyCount).isZero();
    }

    private int createAccount(String userId, String role) throws Exception {
        return mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private Long createAccountAndReadId(String userId, String role) throws Exception {
        String body = mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
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
}