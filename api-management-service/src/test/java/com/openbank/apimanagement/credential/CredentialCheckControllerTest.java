package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.exception.MalformedPersistedScopesException;
import com.openbank.apimanagement.subscription.SubscriptionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CredentialCheckController.class)
@AutoConfigureMockMvc(addFilters = false)
class CredentialCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CredentialCheckService credentialCheckService;

    @Test
    void authenticatedResponseIsReturnedWith200() throws Exception {
        when(credentialCheckService.check("Basic dmFsaWQ6c3VwZXItc2VjcmV0", "/payments", "v1"))
                .thenReturn(CredentialCheckResponse.authenticated(
                        10L, 42L, new SubscriptionPolicy(15L, "Developer", 100, 60, "payments:read"),
                        "payments:read"));

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Basic dmFsaWQ6c3VwZXItc2VjcmV0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.applicationId").value(10))
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(jsonPath("$.subscribed").value(true))
                .andExpect(jsonPath("$.tierId").value(15))
                .andExpect(jsonPath("$.tierName").value("Developer"))
                .andExpect(jsonPath("$.requestsPerWindow").value(100))
                .andExpect(jsonPath("$.windowSeconds").value(60))
                .andExpect(jsonPath("$.scopes").value("payments:read"));
    }

    @Test
    void unrestrictedEffectiveScopesAreReturnedAsAnEmptyString() throws Exception {
        when(credentialCheckService.check("Basic dmFsaWQ6Y3VycmVudA==", "/payments", "v1"))
                .thenReturn(CredentialCheckResponse.authenticated(11L, 43L, null, ""));

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Basic dmFsaWQ6Y3VycmVudA=="))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.subscribed").value(false))
                .andExpect(jsonPath("$.scopes").value(""));
    }

    @Test
    void unauthenticatedResponseIsReturnedWith401() throws Exception {
        when(credentialCheckService.check("Basic dmFsaWQ6d3Jvbmctc2VjcmV0", "/payments", "v1"))
                .thenReturn(CredentialCheckResponse.unauthorized());

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Basic dmFsaWQ6d3Jvbmctc2VjcmV0"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.applicationId").doesNotExist())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.scopes").doesNotExist());
    }

    @Test
    void missingAuthorizationHeaderIsReturnedWith401() throws Exception {
        when(credentialCheckService.check(null, "/payments", "v1"))
                .thenReturn(CredentialCheckResponse.unauthorized());

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void malformedPersistedScopesReturnControlled500WithoutLeakingDetails() throws Exception {
        when(credentialCheckService.check("Basic dmFsaWQ6c3VwZXItc2VjcmV0", "/payments", "v1"))
                .thenThrow(new MalformedPersistedScopesException(
                        new IllegalArgumentException("invalid scope token")));

        mockMvc.perform(get("/internal/credential-check")
                        .param("contextPath", "/payments")
                        .param("version", "v1")
                        .header("Authorization", "Basic dmFsaWQ6c3VwZXItc2VjcmV0"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.fieldErrors.scopes").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("invalid scope token")
                        .doesNotContain("scope")
                        .doesNotContain("IllegalArgumentException")
                        .doesNotContain("at com.openbank"));
    }
}