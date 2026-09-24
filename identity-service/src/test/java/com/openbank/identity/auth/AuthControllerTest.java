package com.openbank.identity.auth;

import com.openbank.identity.exception.AuthenticationFailedException;
import com.openbank.identity.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    private static final String VALID_BODY = """
            {
              "email": "dev@example.com",
              "password": "Correct-Horse-42"
            }
            """;

    @Test
    void loginReturns200WithSafeIdentityAndToken() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResponse("header.payload.signature", "Bearer", 3600L, 123L, "dev@example.com", UserRole.DEVELOPER));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("header.payload.signature"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.userId").value(123))
                .andExpect(jsonPath("$.email").value("dev@example.com"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("Correct-Horse-42")
                            .doesNotContain("passwordHash")
                            .doesNotContain("test-jwt-secret-value");
                });
    }

    @Test
    void loginWithInvalidCredentialsReturns401WithStableContract() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenThrow(new AuthenticationFailedException());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.path").value("/auth/login"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .as("failure must not reveal email existence or leak secrets")
                            .doesNotContain("not exist")
                            .doesNotContain("not found")
                            .doesNotContain("passwordHash")
                            .doesNotContain("Correct-Horse-42")
                            .doesNotContain("at com.openbank")
                            .doesNotContain("java.lang");
                });
    }

    @Test
    void loginRejectsMissingEmailWith400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "password": "Correct-Horse-42"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").value("email is required"));

        verify(authService, never()).login(any(LoginRequest.class));
    }

    @Test
    void loginRejectsBlankEmailWith400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "   ",
                                  "password": "Correct-Horse-42"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        verify(authService, never()).login(any(LoginRequest.class));
    }

    @Test
    void loginRejectsMissingPasswordWith400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dev@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.password").value("password is required"));

        verify(authService, never()).login(any(LoginRequest.class));
    }

    @Test
    void loginRejectsBlankPasswordWith400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dev@example.com",
                                  "password": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        verify(authService, never()).login(any(LoginRequest.class));
    }

    @Test
    void revokeWithBearerTokenRevokesTheAuthenticatedJwt() throws Exception {
        when(authService.revoke("header.payload.signature")).thenReturn(new RevokeResponse(true));

        mockMvc.perform(post("/auth/revoke")
                        .header("Authorization", "Bearer header.payload.signature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("header.payload.signature")
                            .doesNotContain("Correct-Horse-42");
                });

        verify(authService).revoke("header.payload.signature");
    }

    @Test
    void revokeWithoutAuthorizationHeaderReturns401() throws Exception {
        mockMvc.perform(post("/auth/revoke"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"));

        verify(authService, never()).revoke(any());
    }

    @Test
    void revokeWithMalformedAuthorizationHeaderReturns401() throws Exception {
        mockMvc.perform(post("/auth/revoke").header("Authorization", "Basic Zm9vOmJhcg=="))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/auth/revoke").header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verify(authService, never()).revoke(any());
    }
}