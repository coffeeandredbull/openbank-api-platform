package com.openbank.payment.account;

import com.openbank.payment.auth.JwtIdentity;
import com.openbank.payment.auth.UserRole;
import com.openbank.payment.exception.AccountNotFoundException;
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

@WebMvcTest(AccountController.class)
@AutoConfigureMockMvc(addFilters = false)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    private static final Long OWNER_ID = 42L;

    @BeforeEach
    void authenticateAsDefaultDeveloper() {
        authenticate(OWNER_ID, UserRole.DEVELOPER);
    }

    @AfterEach
    void resetSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createReturns201WithBodyAndLocationHeader() throws Exception {
        when(accountService.create(eq(42L), any(CreateAccountRequest.class)))
                .thenReturn(new AccountResponse(1L, 42L, "LKR", TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/accounts/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void createDerivesOwnerFromAuthenticatedPrincipal() throws Exception {
        when(accountService.create(eq(42L), any(CreateAccountRequest.class)))
                .thenReturn(new AccountResponse(1L, 42L, "LKR", TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isCreated());

        verify(accountService).create(eq(42L), any(CreateAccountRequest.class));
    }

    @Test
    void createAcceptsSuppliedCurrency() throws Exception {
        when(accountService.create(eq(42L), any(CreateAccountRequest.class)))
                .thenReturn(new AccountResponse(1L, 42L, "USD", TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void createRejectsInvalidCurrencyWith400() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currency": "eur"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.currency").value("currency must be a valid 3-letter uppercase currency code"));
    }

    @Test
    void createRejectsMalformedJsonWith400() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currency": "USD",
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("Unexpected character")
                            .doesNotContain("com.fasterxml");
                });
    }

    @Test
    void getReturns200WithAccountDetails() throws Exception {
        when(accountService.get(1L, 42L))
                .thenReturn(new AccountResponse(1L, 42L, "LKR", TIMESTAMP, TIMESTAMP));

        mockMvc.perform(get("/accounts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(jsonPath("$.currency").value("LKR"));
    }

    @Test
    void getReturnsStructured404WhenAccountDoesNotExist() throws Exception {
        when(accountService.get(999L, 42L)).thenThrow(new AccountNotFoundException(999L));

        mockMvc.perform(get("/accounts/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/accounts/999"))
                .andExpect(jsonPath("$.message").value("Account with id 999 does not exist"));
    }

    @Test
    void listReturns200WithOwnedAccount() throws Exception {
        when(accountService.list(42L))
                .thenReturn(List.of(new AccountResponse(1L, 42L, "LKR", TIMESTAMP, TIMESTAMP)));

        mockMvc.perform(get("/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].ownerUserId").value(42))
                .andExpect(jsonPath("$[0].currency").value("LKR"));
    }

    @Test
    void listReturnsEmptyArrayWhenNoAccountExists() throws Exception {
        when(accountService.list(42L)).thenReturn(List.of());

        mockMvc.perform(get("/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private void authenticate(Long userId, UserRole role) {
        JwtIdentity identity = new JwtIdentity(userId, role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        identity,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}