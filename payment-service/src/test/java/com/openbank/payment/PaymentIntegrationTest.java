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

import java.math.BigDecimal;
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
class PaymentIntegrationTest {

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
    void createPersistsPaymentWithDerivedCurrencyAndPendingStatus() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");

        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50", "Lunch");

        String currencyStored = jdbcTemplate.queryForObject(
                "select currency from payments where id = ?", String.class, paymentId);
        assertThat(currencyStored).isEqualTo("LKR");
        String statusStored = jdbcTemplate.queryForObject(
                "select status from payments where id = ?", String.class, paymentId);
        assertThat(statusStored).isEqualTo("PENDING");
        Long accountIdStored = jdbcTemplate.queryForObject(
                "select account_id from payments where id = ?", Long.class, paymentId);
        assertThat(accountIdStored).isEqualTo(accountId);
        BigDecimal amountStored = jdbcTemplate.queryForObject(
                "select amount from payments where id = ?", BigDecimal.class, paymentId);
        assertThat(amountStored).isEqualByComparingTo(new BigDecimal("25.50"));
    }

    @Test
    void createUsesAccountCurrencyAndIgnoresClientSuppliedCurrency() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");

        mockMvc.perform(post("/payments")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "amount": 10.00,
                                  "currency": "USD"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("LKR"));

        String currencyStored = jdbcTemplate.queryForObject(
                "select currency from payments order by id asc limit 1", String.class);
        assertThat(currencyStored).isEqualTo("LKR");
    }

    @Test
    void createRejectsAccountOfAnotherUserWith404() throws Exception {
        Long accountId = createAccountAndReadId("77", "DEVELOPER");

        mockMvc.perform(post("/payments")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "amount": 10.00
                                }
                                """.formatted(accountId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from payments", Long.class)).isZero();
    }

    @Test
    void createRejectsMissingAccountWith404() throws Exception {
        mockMvc.perform(post("/payments")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 98765,
                                  "amount": 10.00
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account with id 98765 does not exist"));
    }

    @Test
    void createRejectsNonNumericAmountWith400() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");

        mockMvc.perform(post("/payments")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "amount": "many"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.amount").value("invalid value"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from payments", Long.class)).isZero();
    }

    @Test
    void creatingPaymentDoesNotAutomaticallyCreateTransactions() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50", null);

        assertThat(jdbcTemplate.queryForObject("select count(*) from transactions", Long.class)).isZero();
    }

    @Test
    void getReturnsOwnPayment() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50", "Lunch");

        mockMvc.perform(get("/payments/" + paymentId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId))
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.amount").value(25.50))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getReturns404ForAnotherUsersPayment() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50", null);

        mockMvc.perform(get("/payments/" + paymentId)
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("Lunch");
                });
    }

    @Test
    void listReturnsOnlyOwnPaymentsOrderedByIdAsc() throws Exception {
        Long account42 = createAccountAndReadId("42", "DEVELOPER");
        Long account77 = createAccountAndReadId("77", "DEVELOPER");

        Long paymentA = createPaymentAndReadId("42", "DEVELOPER", account42, "10.00", "Anna");
        Long paymentB = createPaymentAndReadId("42", "DEVELOPER", account42, "20.00", "Anna2");
        createPaymentAndReadId("77", "DEVELOPER", account77, "30.00", "Bob");

        mockMvc.perform(get("/payments")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(paymentA))
                .andExpect(jsonPath("$[1].id").value(paymentB))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("Bob");
                });

        mockMvc.perform(get("/payments")
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(paymentB + 1));
    }

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 1,
                                  "amount": 10.00
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void paymentsTableHasExpectedSchemaAndConstraints() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'payments' "
                        + "order by ordinal_position",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "account_id", "amount", "currency", "description", "status", "created_at", "updated_at");

        List<String> nullableColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'payments' "
                        + "and is_nullable = 'YES'",
                String.class);
        assertThat(nullableColumns).containsExactlyInAnyOrder("description");

        String dataType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'payments' and column_name = 'amount'",
                String.class);
        assertThat(dataType).isEqualTo("numeric");
        Integer precision = jdbcTemplate.queryForObject(
                "select numeric_precision from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'payments' and column_name = 'amount'",
                Integer.class);
        assertThat(precision).isEqualTo(19);
        Integer scale = jdbcTemplate.queryForObject(
                "select numeric_scale from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'payments' and column_name = 'amount'",
                Integer.class);
        assertThat(scale).isEqualTo(2);

        List<String> foreignKeyTargets = jdbcTemplate.queryForList(
                "select con.confrelid::regclass::text from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "where rel.relname = 'payments' and con.contype = 'f' "
                        + "order by 1",
                String.class);
        assertThat(foreignKeyTargets).containsExactlyInAnyOrder("accounts");
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

    private Long createPaymentAndReadId(String userId, String role, Long accountId, String amount, String description) throws Exception {
        String body = mockMvc.perform(post("/payments")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "amount": %s,
                                  "description": %s
                                }
                                """.formatted(accountId, amount,
                                description == null ? "null" : "\"" + description + "\"")))
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