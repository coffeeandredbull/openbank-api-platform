package com.openbank.payment;

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
class TransactionIntegrationTest {

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
    void createPersistsTransactionWithTypePaymentAndDerivedCurrency() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50");

        Long transactionId = createTransactionAndReadId("42", "DEVELOPER", accountId, paymentId, "25.50");

        String typeStored = jdbcTemplate.queryForObject(
                "select type from transactions where id = ?", String.class, transactionId);
        assertThat(typeStored).isEqualTo("PAYMENT");
        String currencyStored = jdbcTemplate.queryForObject(
                "select currency from transactions where id = ?", String.class, transactionId);
        assertThat(currencyStored).isEqualTo("LKR");
        Long accountIdStored = jdbcTemplate.queryForObject(
                "select account_id from transactions where id = ?", Long.class, transactionId);
        assertThat(accountIdStored).isEqualTo(accountId);
        Long paymentIdStored = jdbcTemplate.queryForObject(
                "select payment_id from transactions where id = ?", Long.class, transactionId);
        assertThat(paymentIdStored).isEqualTo(paymentId);
    }

    @Test
    void createRejectsMismatchedAccountAndPaymentWith404() throws Exception {
        Long accountA = createAccountAndReadId("42", "DEVELOPER");
        Long accountB = createAccountAndReadId("77", "DEVELOPER");
        Long paymentA = createPaymentAndReadId("42", "DEVELOPER", accountA, "25.50");

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "paymentId": %d,
                                  "amount": 25.50
                                }
                                """.formatted(accountB, paymentA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Payment with id " + paymentA + " does not exist"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from transactions", Long.class)).isZero();
    }

    @Test
    void createRejectsPaymentBelongingToAnotherUsersAccount() throws Exception {
        Long account77 = createAccountAndReadId("77", "DEVELOPER");
        Long account42 = createAccountAndReadId("42", "DEVELOPER");
        Long payment77 = createPaymentAndReadId("77", "DEVELOPER", account77, "30.00");

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "paymentId": %d,
                                  "amount": 25.50
                                }
                                """.formatted(account42, payment77)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from transactions", Long.class)).isZero();
    }

    @Test
    void createRejectsAccountOfAnotherUserWith404() throws Exception {
        Long account77 = createAccountAndReadId("77", "DEVELOPER");
        Long payment77 = createPaymentAndReadId("77", "DEVELOPER", account77, "30.00");

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "paymentId": %d,
                                  "amount": 25.50
                                }
                                """.formatted(account77, payment77)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from transactions", Long.class)).isZero();
    }

    @Test
    void createIgnoresClientSuppliedTypeAndCurrency() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50");

        Long transactionId = createTransactionAndReadId("42", "DEVELOPER", accountId, paymentId, "25.50");

        String typeStored = jdbcTemplate.queryForObject(
                "select type from transactions where id = ?", String.class, transactionId);
        assertThat(typeStored).isEqualTo("PAYMENT");
        String currencyStored = jdbcTemplate.queryForObject(
                "select currency from transactions where id = ?", String.class, transactionId);
        assertThat(currencyStored).isEqualTo("LKR");
    }

    @Test
    void getReturnsOwnTransaction() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50");
        Long transactionId = createTransactionAndReadId("42", "DEVELOPER", accountId, paymentId, "25.50");

        mockMvc.perform(get("/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transactionId))
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.type").value("PAYMENT"))
                .andExpect(jsonPath("$.currency").value("LKR"));
    }

    @Test
    void getReturns404ForAnotherUsersTransaction() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50");
        Long transactionId = createTransactionAndReadId("42", "DEVELOPER", accountId, paymentId, "25.50");

        mockMvc.perform(get("/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/transactions/" + transactionId));
    }

    @Test
    void listReturnsOnlyOwnTransactionsOrderedByIdAsc() throws Exception {
        Long account42 = createAccountAndReadId("42", "DEVELOPER");
        Long account77 = createAccountAndReadId("77", "DEVELOPER");
        Long paymentA = createPaymentAndReadId("42", "DEVELOPER", account42, "10.00");
        Long paymentB = createPaymentAndReadId("42", "DEVELOPER", account42, "20.00");
        createPaymentAndReadId("77", "DEVELOPER", account77, "30.00");

        Long txA = createTransactionAndReadId("42", "DEVELOPER", account42, paymentA, "10.00");
        Long txB = createTransactionAndReadId("42", "DEVELOPER", account42, paymentB, "20.00");

        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(txA))
                .andExpect(jsonPath("$[1].id").value(txB));

        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/transactions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 1,
                                  "paymentId": 2,
                                  "amount": 10.00
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void transactionsTableHasExpectedSchemaAndConstraints() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'transactions' "
                        + "order by ordinal_position",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "account_id", "payment_id", "type", "amount", "currency", "created_at");

        List<String> nullableColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'transactions' "
                        + "and is_nullable = 'YES'",
                String.class);
        assertThat(nullableColumns).isEmpty();

        String dataType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'transactions' and column_name = 'amount'",
                String.class);
        assertThat(dataType).isEqualTo("numeric");
        Integer precision = jdbcTemplate.queryForObject(
                "select numeric_precision from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'transactions' and column_name = 'amount'",
                Integer.class);
        assertThat(precision).isEqualTo(19);
        Integer scale = jdbcTemplate.queryForObject(
                "select numeric_scale from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'transactions' and column_name = 'amount'",
                Integer.class);
        assertThat(scale).isEqualTo(2);

        List<String> foreignKeyTargets = jdbcTemplate.queryForList(
                "select con.confrelid::regclass::text from pg_constraint con "
                        + "join pg_class rel on rel.oid = con.conrelid "
                        + "where rel.relname = 'transactions' and con.contype = 'f' "
                        + "order by 1",
                String.class);
        assertThat(foreignKeyTargets).containsExactlyInAnyOrder("accounts", "payments");
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

    private Long createPaymentAndReadId(String userId, String role, Long accountId, String amount) throws Exception {
        String body = mockMvc.perform(post("/payments")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "amount": %s
                                }
                                """.formatted(accountId, amount)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private Long createTransactionAndReadId(String userId, String role, Long accountId, Long paymentId, String amount) throws Exception {
        String body = mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + token(userId, role, 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "paymentId": %d,
                                  "amount": %s,
                                  "type": "REFUND",
                                  "currency": "USD"
                                }
                                """.formatted(accountId, paymentId, amount)))
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