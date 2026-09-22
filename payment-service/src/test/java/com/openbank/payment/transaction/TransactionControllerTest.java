package com.openbank.payment.transaction;

import com.openbank.payment.auth.JwtIdentity;
import com.openbank.payment.auth.UserRole;
import com.openbank.payment.exception.TransactionNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@AutoConfigureMockMvc(addFilters = false)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionService transactionService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    private static final BigDecimal AMOUNT = new BigDecimal("25.50");

    @BeforeEach
    void authenticateAsDefaultDeveloper() {
        JwtIdentity identity = new JwtIdentity(42L, UserRole.DEVELOPER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        identity,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + identity.role().name()))));
    }

    @AfterEach
    void resetSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        when(transactionService.create(eq(42L), any(CreateTransactionRequest.class)))
                .thenReturn(new TransactionResponse(7L, 10L, 20L, TransactionType.PAYMENT, AMOUNT, "LKR", TIMESTAMP));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "paymentId": 20,
                                  "amount": 25.50
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/transactions/7"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.accountId").value(10))
                .andExpect(jsonPath("$.paymentId").value(20))
                .andExpect(jsonPath("$.type").value("PAYMENT"))
                .andExpect(jsonPath("$.amount").value(25.50))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void createDerivesOwnerFromAuthenticatedPrincipal() throws Exception {
        when(transactionService.create(eq(42L), any(CreateTransactionRequest.class)))
                .thenReturn(new TransactionResponse(7L, 10L, 20L, TransactionType.PAYMENT, AMOUNT, "LKR", TIMESTAMP));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "paymentId": 20,
                                  "amount": 25.50
                                }
                                """))
                .andExpect(status().isCreated());

        verify(transactionService).create(eq(42L), any(CreateTransactionRequest.class));
    }

    @Test
    void createRejectsMissingAccountIdWith400() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentId": 20,
                                  "amount": 25.50
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.accountId").value("accountId is required"));
    }

    @Test
    void createRejectsMissingPaymentIdWith400() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "amount": 25.50
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.paymentId").value("paymentId is required"));
    }

    @Test
    void createRejectsMissingAmountWith400() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "paymentId": 20
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.amount").value("amount is required"));
    }

    @Test
    void createRejectsZeroAmountWith400() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "paymentId": 20,
                                  "amount": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.amount").value("amount must be greater than zero"));
    }

    @Test
    void createRejectsNegativeAmountWith400() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "paymentId": 20,
                                  "amount": -5
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.amount").value("amount must be greater than zero"));
    }

    @Test
    void getReturns200WithOwnedTransaction() throws Exception {
        when(transactionService.get(7L, 42L))
                .thenReturn(new TransactionResponse(7L, 10L, 20L, TransactionType.PAYMENT, AMOUNT, "LKR", TIMESTAMP));

        mockMvc.perform(get("/transactions/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.type").value("PAYMENT"))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());
    }

    @Test
    void getReturnsStructured404WhenTransactionDoesNotExist() throws Exception {
        when(transactionService.get(999L, 42L)).thenThrow(new TransactionNotFoundException(999L));

        mockMvc.perform(get("/transactions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/transactions/999"))
                .andExpect(jsonPath("$.message").value("Transaction with id 999 does not exist"));
    }

    @Test
    void listReturns200WithOwnedTransactionsOrderedById() throws Exception {
        when(transactionService.list(42L))
                .thenReturn(List.of(
                        new TransactionResponse(1L, 10L, 20L, TransactionType.PAYMENT, AMOUNT, "LKR", TIMESTAMP),
                        new TransactionResponse(2L, 10L, 21L, TransactionType.PAYMENT, new BigDecimal("5.00"), "LKR", TIMESTAMP)));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].type").value("PAYMENT"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[0].ownerUserId").doesNotExist())
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist());
    }
}