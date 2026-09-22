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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class TransactionSecurityIntegrationTest {

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
    void developerCanCreateListAndGetOwnTransactions() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50");
        Long transactionId = createTransactionAndReadId("42", "DEVELOPER", accountId, paymentId, "25.50");

        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(transactionId));

        mockMvc.perform(get("/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.type").value("PAYMENT"));
    }

    @Test
    void adminCanCreateListAndGetOwnTransactions() throws Exception {
        Long accountId = createAccountAndReadId("1", "ADMIN");
        Long paymentId = createPaymentAndReadId("1", "ADMIN", accountId, "25.50");
        Long transactionId = createTransactionAndReadId("1", "ADMIN", accountId, paymentId, "25.50");

        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId));
    }

    @Test
    void developerCannotCreateTransactionForAnotherUsersAccount() throws Exception {
        Long account77 = createAccountAndReadId("77", "DEVELOPER");
        Long payment77 = createPaymentAndReadId("77", "DEVELOPER", account77, "30.00");

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "paymentId": %d,
                                  "amount": 10.00
                                }
                                """.formatted(account77, payment77)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from transactions", Long.class)).isZero();
    }

    @Test
    void adminCannotCreateTransactionForAnotherDevelopersAccount() throws Exception {
        Long account42 = createAccountAndReadId("42", "DEVELOPER");
        Long payment42 = createPaymentAndReadId("42", "DEVELOPER", account42, "25.50");

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "paymentId": %d,
                                  "amount": 10.00
                                }
                                """.formatted(account42, payment42)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void developerCannotCreateTransactionWithMismatchedAccountAndPayment() throws Exception {
        Long account42 = createAccountAndReadId("42", "DEVELOPER");
        Long account77 = createAccountAndReadId("77", "DEVELOPER");
        Long payment42 = createPaymentAndReadId("42", "DEVELOPER", account42, "25.50");

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": %d,
                                  "paymentId": %d,
                                  "amount": 10.00
                                }
                                """.formatted(account77, payment42)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void developerCannotGetAnotherDevelopersTransaction() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50");
        Long transactionId = createTransactionAndReadId("42", "DEVELOPER", accountId, paymentId, "25.50");

        mockMvc.perform(get("/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token("77", "DEVELOPER", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void adminCannotAccessAnotherDevelopersTransaction() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50");
        Long transactionId = createTransactionAndReadId("42", "DEVELOPER", accountId, paymentId, "25.50");

        mockMvc.perform(get("/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("25.50");
                });
    }

    @Test
    void adminListDoesNotReturnAnotherUsersTransactions() throws Exception {
        Long accountId = createAccountAndReadId("42", "DEVELOPER");
        Long paymentId = createPaymentAndReadId("42", "DEVELOPER", accountId, "25.50");
        createTransactionAndReadId("42", "DEVELOPER", accountId, paymentId, "25.50");

        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
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
    void invalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + token("999", "ADMIN", -3600)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tamperedTokenReturns401() throws Exception {
        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + tokenWithDifferentSecret("1", "ADMIN")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unknownRoleFailsClosedTo401() throws Exception {
        mockMvc.perform(get("/transactions")
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
                                  "amount": %s
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