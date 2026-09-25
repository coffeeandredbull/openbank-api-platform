package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.auth.JwtIdentity;
import com.openbank.apimanagement.auth.UserRole;
import com.openbank.apimanagement.exception.CredentialNotFoundException;
import com.openbank.apimanagement.exception.InvalidCredentialStatusTransitionException;
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
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({CredentialController.class, ApplicationCredentialController.class})
@AutoConfigureMockMvc(addFilters = false)
class CredentialControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CredentialService credentialService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-22T10:00:00Z");

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
    void createReturns201WithLocationHeaderAndSecretOnlyHere() throws Exception {
        when(credentialService.create(eq(42L), any(CreateCredentialRequest.class)))
                .thenReturn(new CredentialCreatedResponse(1L, 10L, "client-abc", "plain-secret-xyz",
                        CredentialStatus.ACTIVE, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 10
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/credentials/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.applicationId").value(10))
                .andExpect(jsonPath("$.clientId").value("client-abc"))
                .andExpect(jsonPath("$.clientSecret").value("plain-secret-xyz"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.clientSecretHash").doesNotExist())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());
    }

    @Test
    void createDerivesOwnerFromAuthenticatedPrincipal() throws Exception {
        when(credentialService.create(eq(42L), any(CreateCredentialRequest.class)))
                .thenReturn(new CredentialCreatedResponse(1L, 10L, "client-abc", "secret",
                        CredentialStatus.ACTIVE, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 10
                                }
                                """))
                .andExpect(status().isCreated());

        verify(credentialService).create(eq(42L), any(CreateCredentialRequest.class));
    }

    @Test
    void createIgnoresClientProvidedClientIdAndSecret() throws Exception {
        when(credentialService.create(eq(42L), any(CreateCredentialRequest.class)))
                .thenReturn(new CredentialCreatedResponse(1L, 10L, "server-generated", "server-secret",
                        CredentialStatus.ACTIVE, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 10,
                                  "clientId": "client-supplied-id",
                                  "clientSecret": "client-supplied-secret"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").value("server-generated"))
                .andExpect(jsonPath("$.clientSecret").value("server-secret"));

        verify(credentialService).create(
                eq(42L),
                org.mockito.ArgumentMatchers.argThat(request -> request.applicationId().equals(10L)));
    }

    @Test
    void applicationScopedCreateReturnsExpirationAndUsesPathApplication() throws Exception {
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        when(credentialService.create(42L, 10L, expiresAt))
                .thenReturn(new CredentialCreatedResponse(
                        1L, 10L, "client-abc", "plain-secret-xyz",
                        CredentialStatus.ACTIVE, expiresAt, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/applications/10/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expiresAt": "2030-01-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/credentials/1"))
                .andExpect(jsonPath("$.applicationId").value(10))
                .andExpect(jsonPath("$.expiresAt").value("2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.clientSecretHash").doesNotExist());

        verify(credentialService).create(42L, 10L, expiresAt);
    }

    @Test
    void createRejectsMissingApplicationIdWith400() throws Exception {
        mockMvc.perform(post("/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.applicationId").value("applicationId is required"));
    }

    @Test
    void createRejectsPastExpiresAtWith400() throws Exception {
        mockMvc.perform(post("/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 10,
                                  "expiresAt": "2020-01-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.expiresAt").value("expiresAt must be in the future"));

        verify(credentialService, never()).create(eq(42L), any(CreateCredentialRequest.class));
    }

    @Test
    void applicationScopedCreateRejectsPastExpiresAtWith400() throws Exception {
        mockMvc.perform(post("/applications/10/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expiresAt": "2020-01-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.expiresAt").value("expiresAt must be in the future"));

        verify(credentialService, never()).create(eq(42L), eq(10L), any(Instant.class));
    }

    @Test
    void createRejectsMalformedExpiresAtWith400() throws Exception {
        mockMvc.perform(post("/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 10,
                                  "expiresAt": "not-an-instant"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verify(credentialService, never()).create(eq(42L), any(CreateCredentialRequest.class));
    }

    @Test
    void createRejectsMalformedJsonWith400() throws Exception {
        mockMvc.perform(post("/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 10,
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
    void getReturns200WithCredentialDetailsWithoutSecret() throws Exception {
        when(credentialService.get(1L, 42L))
                .thenReturn(new CredentialResponse(1L, 10L, "client-abc", CredentialStatus.ACTIVE, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(get("/credentials/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.applicationId").value(10))
                .andExpect(jsonPath("$.clientId").value("client-abc"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").value(nullValue()))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.clientSecretHash").doesNotExist())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());
    }

    @Test
    void getReturnsStructured404WhenCredentialDoesNotExistOrOwenedByAnother() throws Exception {
        when(credentialService.get(999L, 42L)).thenThrow(new CredentialNotFoundException(999L));

        mockMvc.perform(get("/credentials/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/credentials/999"))
                .andExpect(jsonPath("$.message").value("Credential with id 999 does not exist"));
    }

    @Test
    void listReturns200WithOwnedCredentialsWithoutSecrets() throws Exception {
        when(credentialService.list(42L))
                .thenReturn(List.of(
                        new CredentialResponse(1L, 10L, "client-1", CredentialStatus.ACTIVE, TIMESTAMP, TIMESTAMP),
                        new CredentialResponse(2L, 10L, "client-2", CredentialStatus.REVOKED, TIMESTAMP, TIMESTAMP)));

        mockMvc.perform(get("/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].clientId").value("client-1"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].clientId").value("client-2"))
                .andExpect(jsonPath("$[1].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$[0].clientSecretHash").doesNotExist())
                .andExpect(jsonPath("$[0].ownerUserId").doesNotExist());
    }

    @Test
    void patchStatusReturns200WithRevokedCredentialWithoutSecret() throws Exception {
        when(credentialService.changeStatus(eq(1L), any(UpdateCredentialStatusRequest.class)))
                .thenReturn(new CredentialResponse(1L, 10L, "client-abc", CredentialStatus.REVOKED, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/credentials/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REVOKED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.applicationId").value(10))
                .andExpect(jsonPath("$.clientId").value("client-abc"))
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.clientSecretHash").doesNotExist());

        verify(credentialService).changeStatus(eq(1L),
                org.mockito.ArgumentMatchers.argThat(request -> request.status() == CredentialStatus.REVOKED));
    }

    @Test
    void patchStatusRequiresStatusField() throws Exception {
        mockMvc.perform(patch("/credentials/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("status is required"));
    }

    @Test
    void patchStatusRejectsInvalidStatusValue() throws Exception {
        mockMvc.perform(patch("/credentials/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "SUSPENDED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("invalid value"));
    }

    @Test
    void patchStatusConflictProducesStructured409() throws Exception {
        when(credentialService.changeStatus(eq(1L), any(UpdateCredentialStatusRequest.class)))
                .thenThrow(new InvalidCredentialStatusTransitionException(
                        CredentialStatus.REVOKED, CredentialStatus.ACTIVE));

        mockMvc.perform(patch("/credentials/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/credentials/1/status"))
                .andExpect(jsonPath("$.message").value("Credential status transition from REVOKED to ACTIVE is not allowed"));
    }

    @Test
    void patchStatusMissingCredentialProducesStructured404() throws Exception {
        when(credentialService.changeStatus(eq(999L), any(UpdateCredentialStatusRequest.class)))
                .thenThrow(new CredentialNotFoundException(999L));

        mockMvc.perform(patch("/credentials/999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "REVOKED"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Credential with id 999 does not exist"));
    }

    @Test
    void rotateReturns200WithNewSecretExactlyOnce() throws Exception {
        when(credentialService.rotate(1L))
                .thenReturn(new CredentialRotatedResponse(1L, 10L, "new-client-id", "new-secret-once",
                        CredentialStatus.ACTIVE, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/credentials/1/rotate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.applicationId").value(10))
                .andExpect(jsonPath("$.clientId").value("new-client-id"))
                .andExpect(jsonPath("$.clientSecret").value("new-secret-once"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.clientSecretHash").doesNotExist())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());

        verify(credentialService).rotate(1L);
    }

    @Test
    void rotateMissingCredentialProducesStructured404() throws Exception {
        when(credentialService.rotate(999L)).thenThrow(new CredentialNotFoundException(999L));

        mockMvc.perform(post("/credentials/999/rotate"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/credentials/999/rotate"));
    }

    @Test
    void rotateRevokedCredentialProducesStructured409() throws Exception {
        when(credentialService.rotate(1L))
                .thenThrow(new InvalidCredentialStatusTransitionException(1L, CredentialStatus.REVOKED));

        mockMvc.perform(post("/credentials/1/rotate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Credential with id 1 is REVOKED and cannot be rotated"));
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