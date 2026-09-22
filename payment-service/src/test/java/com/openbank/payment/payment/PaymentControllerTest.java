package com.openbank.payment.payment;

import com.openbank.payment.auth.JwtIdentity;
import com.openbank.payment.auth.UserRole;
import com.openbank.payment.exception.PaymentNotFoundException;
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

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

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
        when(paymentService.create(eq(42L), any(CreatePaymentRequest.class)))
                .thenReturn(new PaymentResponse(1L, 10L, AMOUNT, "LKR", "Lunch", PaymentStatus.PENDING, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "amount": 25.50,
                                  "description": "Lunch"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/payments/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.accountId").value(10))
                .andExpect(jsonPath("$.amount").value(25.50))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.description").value("Lunch"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void createDerivesOwnerFromAuthenticatedPrincipal() throws Exception {
        when(paymentService.create(eq(42L), any(CreatePaymentRequest.class)))
                .thenReturn(new PaymentResponse(1L, 10L, AMOUNT, "LKR", null, PaymentStatus.PENDING, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "amount": 25.50
                                }
                                """))
                .andExpect(status().isCreated());

        verify(paymentService).create(eq(42L), any(CreatePaymentRequest.class));
    }

    @Test
    void createRejectsMissingAccountIdWith400() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 25.50
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.accountId").value("accountId is required"));
    }

    @Test
    void createRejectsMissingAmountWith400() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.amount").value("amount is required"));
    }

    @Test
    void createRejectsZeroAmountWith400() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "amount": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.amount").value("amount must be greater than zero"));
    }

    @Test
    void createRejectsNegativeAmountWith400() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "amount": -5
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.amount").value("amount must be greater than zero"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("StackOverflow").doesNotContain("com.fasterxml");
                });
    }

    @Test
    void createRejectsAmountWithTooManyDecimalPlacesWith400() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                  "amount": 25.999
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.amount").value(
                        "amount must not exceed 17 digits before the decimal point and 2 digits after"));
    }

    @Test
    void createRejectsMalformedJsonWith400() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 10,
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void getReturns200WithOwnedPayment() throws Exception {
        when(paymentService.get(1L, 42L))
                .thenReturn(new PaymentResponse(1L, 10L, AMOUNT, "LKR", "Lunch", PaymentStatus.PENDING, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(get("/payments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.accountId").value(10))
                .andExpect(jsonPath("$.amount").value(25.50))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());
    }

    @Test
    void getReturnsStructured404WhenPaymentDoesNotExist() throws Exception {
        when(paymentService.get(999L, 42L)).thenThrow(new PaymentNotFoundException(999L));

        mockMvc.perform(get("/payments/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/payments/999"))
                .andExpect(jsonPath("$.message").value("Payment with id 999 does not exist"));
    }

    @Test
    void listReturns200WithOwnedPaymentsOrderedById() throws Exception {
        when(paymentService.list(42L))
                .thenReturn(List.of(
                        new PaymentResponse(1L, 10L, AMOUNT, "LKR", null, PaymentStatus.PENDING, TIMESTAMP, TIMESTAMP),
                        new PaymentResponse(2L, 10L, new BigDecimal("5.00"), "LKR", null, PaymentStatus.PENDING, TIMESTAMP, TIMESTAMP)));

        mockMvc.perform(get("/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[0].ownerUserId").doesNotExist())
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist());
    }
}