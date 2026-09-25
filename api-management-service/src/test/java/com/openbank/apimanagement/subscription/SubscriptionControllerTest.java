package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.auth.JwtIdentity;
import com.openbank.apimanagement.auth.UserRole;
import com.openbank.apimanagement.exception.InvalidSubscriptionStatusTransitionException;
import com.openbank.apimanagement.exception.SubscriptionNotFoundException;
import com.openbank.apimanagement.exception.SubscriptionTierNotFoundException;
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

@WebMvcTest(SubscriptionController.class)
@AutoConfigureMockMvc(addFilters = false)
class SubscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubscriptionService subscriptionService;

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
        when(subscriptionService.create(eq(42L), any(CreateSubscriptionRequest.class)))
                .thenReturn(new SubscriptionResponse(
                        1L, 10L, 20L, 15L, "Developer", SubscriptionStatus.PENDING, null, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 10,
                                  "apiVersionId": 20,
                                  "tierId": 15
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/subscriptions/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.applicationId").value(10))
                .andExpect(jsonPath("$.apiVersionId").value(20))
                .andExpect(jsonPath("$.tierId").value(15))
                .andExpect(jsonPath("$.tierName").value("Developer"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @Test
    void createDerivesOwnerFromAuthenticatedPrincipal() throws Exception {
        when(subscriptionService.create(eq(42L), any(CreateSubscriptionRequest.class)))
                .thenReturn(new SubscriptionResponse(
                        1L, 10L, 20L, 15L, "Developer", SubscriptionStatus.PENDING, null, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 10,
                                  "apiVersionId": 20,
                                  "tierId": 15
                                }
                                """))
                .andExpect(status().isCreated());

        verify(subscriptionService).create(eq(42L), any(CreateSubscriptionRequest.class));
    }

    @Test
    void createRejectsMissingTierIdWith400() throws Exception {
        mockMvc.perform(post("/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 10,
                                  "apiVersionId": 20
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.tierId").value("tierId is required"));
    }

    @Test
    void createRejectsMissingApplicationIdWith400() throws Exception {
        mockMvc.perform(post("/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "apiVersionId": 20
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.applicationId").value("applicationId is required"));
    }

    @Test
    void createRejectsMissingApiVersionIdWith400() throws Exception {
        mockMvc.perform(post("/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": 10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.apiVersionId").value("apiVersionId is required"));
    }

    @Test
    void createRejectsNullApplicationIdWith400() throws Exception {
        mockMvc.perform(post("/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": null,
                                  "apiVersionId": 20
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.applicationId").value("applicationId is required"));
    }

    @Test
    void createRejectsMalformedJsonWith400() throws Exception {
        mockMvc.perform(post("/subscriptions")
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
    void getReturns200WithSubscriptionDetails() throws Exception {
        when(subscriptionService.get(1L, 42L))
                .thenReturn(new SubscriptionResponse(
                        1L, 10L, 20L, 15L, "Developer", SubscriptionStatus.ACTIVE, null, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(get("/subscriptions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.applicationId").value(10))
                .andExpect(jsonPath("$.apiVersionId").value(20))
                .andExpect(jsonPath("$.tierId").value(15))
                .andExpect(jsonPath("$.tierName").value("Developer"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());
    }

    @Test
    void getReturnsStructured404WhenSubscriptionDoesNotExist() throws Exception {
        when(subscriptionService.get(999L, 42L)).thenThrow(new SubscriptionNotFoundException(999L));

        mockMvc.perform(get("/subscriptions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_SUBSCRIPTION_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/subscriptions/999"))
                .andExpect(jsonPath("$.message").value("Subscription with id 999 does not exist"));
    }

    @Test
    void listReturns200WithOwnedSubscriptions() throws Exception {
        when(subscriptionService.list(42L))
                .thenReturn(List.of(
                        new SubscriptionResponse(
                                1L, 10L, 20L, 15L, "Developer", SubscriptionStatus.ACTIVE, null, TIMESTAMP, TIMESTAMP),
                        new SubscriptionResponse(
                                2L, 10L, 21L, 15L, "Developer", SubscriptionStatus.DENIED, null, TIMESTAMP, TIMESTAMP)));

        mockMvc.perform(get("/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].applicationId").value(10))
                .andExpect(jsonPath("$[0].tierId").value(15))
                .andExpect(jsonPath("$[0].tierName").value("Developer"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].apiVersionId").value(21))
                .andExpect(jsonPath("$[0].ownerUserId").doesNotExist());
    }

    @Test
    void updateStatusReturns200WithUpdatedSubscription() throws Exception {
        when(subscriptionService.changeStatus(eq(1L), any(UpdateSubscriptionStatusRequest.class)))
                .thenReturn(new SubscriptionResponse(
                        1L, 10L, 20L, 15L, "Developer", SubscriptionStatus.ACTIVE, null, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/subscriptions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.revokedAt").value(nullValue()));
    }

    @Test
    void updateStatusRejectsMissingStatusWith400() throws Exception {
        mockMvc.perform(patch("/subscriptions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("status is required"));
    }

    @Test
    void updateStatusRejectsInvalidStatusValueWith400() throws Exception {
        mockMvc.perform(patch("/subscriptions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ARCHIVED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("invalid value"));
    }

    @Test
    void updateStatusReturnsStructured409ForInvalidTransition() throws Exception {
        when(subscriptionService.changeStatus(eq(1L), any(UpdateSubscriptionStatusRequest.class)))
                .thenThrow(new InvalidSubscriptionStatusTransitionException(
                        SubscriptionStatus.ACTIVE, SubscriptionStatus.PENDING));

        mockMvc.perform(patch("/subscriptions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "PENDING"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SUBSCRIPTION_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/subscriptions/1/status"))
                .andExpect(jsonPath("$.message")
                        .value("Subscription status transition from ACTIVE to PENDING is not allowed"));
    }

    @Test
    void updateStatusReturnsStructured404WhenSubscriptionDoesNotExist() throws Exception {
        when(subscriptionService.changeStatus(eq(999L), any(UpdateSubscriptionStatusRequest.class)))
                .thenThrow(new SubscriptionNotFoundException(999L));

        mockMvc.perform(patch("/subscriptions/999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_SUBSCRIPTION_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/subscriptions/999/status"))
                .andExpect(jsonPath("$.message").value("Subscription with id 999 does not exist"));
    }

    @Test
    void changeTierReturns200WithUpdatedSubscription() throws Exception {
        when(subscriptionService.changeTier(eq(1L), any(ChangeSubscriptionTierRequest.class)))
                .thenReturn(new SubscriptionResponse(
                        1L, 10L, 20L, 16L, "Gold", SubscriptionStatus.PENDING, null, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/subscriptions/1/tier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tierId": 16
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.applicationId").value(10))
                .andExpect(jsonPath("$.apiVersionId").value(20))
                .andExpect(jsonPath("$.tierId").value(16))
                .andExpect(jsonPath("$.tierName").value("Gold"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void changeTierForwardsTheSubscriptionIdAndTierId() throws Exception {
        when(subscriptionService.changeTier(eq(1L), any(ChangeSubscriptionTierRequest.class)))
                .thenReturn(new SubscriptionResponse(
                        1L, 10L, 20L, 16L, "Gold", SubscriptionStatus.PENDING, null, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/subscriptions/1/tier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":16}"))
                .andExpect(status().isOk());

        verify(subscriptionService).changeTier(1L, new ChangeSubscriptionTierRequest(16L));
    }

    @Test
    void changeTierRejectsMissingTierIdWith400() throws Exception {
        mockMvc.perform(patch("/subscriptions/1/tier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.tierId").value("tierId is required"));

        verify(subscriptionService, never()).changeTier(any(), any());
    }

    @Test
    void changeTierRejectsNullTierIdWith400() throws Exception {
        mockMvc.perform(patch("/subscriptions/1/tier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.tierId").value("tierId is required"));

        verify(subscriptionService, never()).changeTier(any(), any());
    }

    @Test
    void changeTierRejectsInvalidTierIdWith400() throws Exception {
        mockMvc.perform(patch("/subscriptions/1/tier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":\"gold\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.tierId").value("invalid value"));

        verify(subscriptionService, never()).changeTier(any(), any());
    }

    @Test
    void changeTierReturnsStructured404WhenSubscriptionDoesNotExist() throws Exception {
        when(subscriptionService.changeTier(eq(999L), any(ChangeSubscriptionTierRequest.class)))
                .thenThrow(new SubscriptionNotFoundException(999L));

        mockMvc.perform(patch("/subscriptions/999/tier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":16}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_SUBSCRIPTION_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/subscriptions/999/tier"))
                .andExpect(jsonPath("$.message").value("Subscription with id 999 does not exist"));
    }

    @Test
    void changeTierReturnsStructured404WhenTargetTierDoesNotExist() throws Exception {
        when(subscriptionService.changeTier(eq(1L), any(ChangeSubscriptionTierRequest.class)))
                .thenThrow(new SubscriptionTierNotFoundException(999L));

        mockMvc.perform(patch("/subscriptions/1/tier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tierId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/subscriptions/1/tier"))
                .andExpect(jsonPath("$.message").value("Subscription tier with id 999 does not exist"));
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