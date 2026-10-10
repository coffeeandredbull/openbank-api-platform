package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.exception.SubscriptionTierAlreadyExistsException;
import com.openbank.apimanagement.exception.SubscriptionTierInUseException;
import com.openbank.apimanagement.exception.SubscriptionTierNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubscriptionTierController.class)
@AutoConfigureMockMvc(addFilters = false)
class SubscriptionTierControllerTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubscriptionTierService subscriptionTierService;

    @Test
    void createReturns201WithLocationAndPublicTierFields() throws Exception {
        when(subscriptionTierService.create(any(CreateSubscriptionTierRequest.class)))
                .thenReturn(new SubscriptionTierResponse(
                        7L, "Premium", "Premium access", 500, 30, 4999, SubscriptionTierStatus.ACTIVE,
                        TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/subscription-tiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Premium",
                                  "description": "Premium access",
                                  "requestsPerWindow": 500,
                                  "windowSeconds": 30
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/subscription-tiers/7"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Premium"))
                .andExpect(jsonPath("$.description").value("Premium access"))
                .andExpect(jsonPath("$.requestsPerWindow").value(500))
                .andExpect(jsonPath("$.windowSeconds").value(30))
                .andExpect(jsonPath("$.priceCentsPerMonth").value(4999))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());

        verify(subscriptionTierService).create(any(CreateSubscriptionTierRequest.class));
    }

    @Test
    void createForwardsTheRequestBody() throws Exception {
        when(subscriptionTierService.create(any(CreateSubscriptionTierRequest.class)))
                .thenReturn(new SubscriptionTierResponse(
                        7L, "Developer", null, 100, 60, 0, SubscriptionTierStatus.ACTIVE, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/subscription-tiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Developer"
                                }
                                """))
                .andExpect(status().isCreated());

        verify(subscriptionTierService).create(eq(new CreateSubscriptionTierRequest("Developer", null, null, null, null)));
    }

    @Test
    void createRejectsMissingName() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "No name"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").value("name is required"));

        verify(subscriptionTierService, never()).create(any(CreateSubscriptionTierRequest.class));
    }

    @Test
    void createRejectsBlankName() throws Exception {
        assertCreateInvalid("name", "\"   \"", "name must not be blank or contain leading or trailing whitespace");
    }

    @Test
    void createRejectsNameWithLeadingOrTrailingWhitespace() throws Exception {
        assertCreateInvalid("name", "\" Gold \"", "name must not be blank or contain leading or trailing whitespace");
    }

    @Test
    void createRejectsNameLongerThan100Characters() throws Exception {
        assertCreateInvalid("name", "\"" + "n".repeat(101) + "\"", "name must not exceed 100 characters");
    }

    @Test
    void createRejectsDescriptionLongerThan500Characters() throws Exception {
        assertCreateInvalid("description", "\"" + "d".repeat(501) + "\"", "description must not exceed 500 characters");
    }

    @Test
    void createRejectsNonPositiveRequestsPerWindow() throws Exception {
        assertCreateInvalid("requestsPerWindow", "0", "requestsPerWindow must be greater than 0");
    }

    @Test
    void createRejectsNonPositiveWindowSeconds() throws Exception {
        assertCreateInvalid("windowSeconds", "-1", "windowSeconds must be greater than 0");
    }

    @Test
    void createRejectsNegativePrice() throws Exception {
        assertCreateInvalid("priceCentsPerMonth", "-1", "priceCentsPerMonth must not be negative");
    }

    @Test
    void createReturns409WhenTierNameAlreadyExists() throws Exception {
        when(subscriptionTierService.create(any(CreateSubscriptionTierRequest.class)))
                .thenThrow(new SubscriptionTierAlreadyExistsException("Silver"));

        mockMvc.perform(post("/subscription-tiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Silver\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_ALREADY_EXISTS"))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .doesNotContain("DataIntegrityViolation")
                        .doesNotContain("unique constraint"));
    }

    @Test
    void listReturnsPublicTierFieldsInIdOrder() throws Exception {
        when(subscriptionTierService.list()).thenReturn(java.util.List.of(
                new SubscriptionTierResponse(
                        1L, "Bronze", "Basic", 100, 60, 0, SubscriptionTierStatus.ACTIVE, TIMESTAMP, TIMESTAMP),
                new SubscriptionTierResponse(
                        2L, "Gold", "Premium", 1000, 60, 0, SubscriptionTierStatus.ACTIVE, TIMESTAMP, TIMESTAMP)));

        mockMvc.perform(get("/subscription-tiers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Bronze"))
                .andExpect(jsonPath("$[0].requestsPerWindow").value(100))
                .andExpect(jsonPath("$[0].windowSeconds").value(60))
                .andExpect(jsonPath("$[0].priceCentsPerMonth").value(0))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].name").value("Gold"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].ownerUserId").doesNotExist());
    }

    @Test
    void listReturnsEmptyArrayWhenNoTiersExist() throws Exception {
        when(subscriptionTierService.list()).thenReturn(java.util.List.of());

        mockMvc.perform(get("/subscription-tiers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private void assertCreateInvalid(String field, String value, String message) throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + field + "\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors." + field).value(message));

        verify(subscriptionTierService, never()).create(any(CreateSubscriptionTierRequest.class));
    }

    @Test
    void updateReturns200WithPublicTierFields() throws Exception {
        when(subscriptionTierService.update(eq(7L), any(UpdateSubscriptionTierRequest.class)))
                .thenReturn(new SubscriptionTierResponse(
                        7L, "Premium", "Premium access", 500, 30, 0, SubscriptionTierStatus.ACTIVE,
                        TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/subscription-tiers/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Premium",
                                  "description": "Premium access",
                                  "requestsPerWindow": 500,
                                  "windowSeconds": 30
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Premium"))
                .andExpect(jsonPath("$.description").value("Premium access"))
                .andExpect(jsonPath("$.requestsPerWindow").value(500))
                .andExpect(jsonPath("$.windowSeconds").value(30))
                .andExpect(jsonPath("$.priceCentsPerMonth").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());
    }

    @Test
    void updateForwardsOmittedFieldsAsNull() throws Exception {
        when(subscriptionTierService.update(eq(7L), any(UpdateSubscriptionTierRequest.class)))
                .thenReturn(new SubscriptionTierResponse(
                        7L, "Gold", "Description", 500, 60, 0, SubscriptionTierStatus.ACTIVE, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/subscription-tiers/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestsPerWindow": 500
                                }
                                """))
                .andExpect(status().isOk());

        verify(subscriptionTierService).update(eq(7L), eq(new UpdateSubscriptionTierRequest(null, null, 500, null, null)));
    }

    @Test
    void updateForwardsPriceOnlyRequestBody() throws Exception {
        when(subscriptionTierService.update(eq(7L), any(UpdateSubscriptionTierRequest.class)))
                .thenReturn(new SubscriptionTierResponse(
                        7L, "Gold", "Description", 100, 60, 4999, SubscriptionTierStatus.ACTIVE,
                        TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/subscription-tiers/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "priceCentsPerMonth": 4999
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceCentsPerMonth").value(4999))
                .andExpect(jsonPath("$.name").value("Gold"));

        verify(subscriptionTierService).update(
                eq(7L), eq(new UpdateSubscriptionTierRequest(null, null, null, null, 4999)));
    }

    @Test
    void updateRejectsEmptyBody() throws Exception {
        mockMvc.perform(patch("/subscription-tiers/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.request").value("at least one field must be supplied"));

        verify(subscriptionTierService, never()).update(any(), any());
    }

    @Test
    void updateRejectsBlankName() throws Exception {
        assertInvalid("name", "\"   \"", "name must not be blank or contain leading or trailing whitespace");
    }

    @Test
    void updateRejectsNameWithLeadingOrTrailingWhitespace() throws Exception {
        assertInvalid("name", "\" Gold \"", "name must not be blank or contain leading or trailing whitespace");
    }

    @Test
    void updateRejectsNameLongerThan100Characters() throws Exception {
        assertInvalid("name", "\"" + "n".repeat(101) + "\"", "name must not exceed 100 characters");
    }

    @Test
    void updateRejectsDescriptionLongerThan500Characters() throws Exception {
        assertInvalid("description", "\"" + "d".repeat(501) + "\"", "description must not exceed 500 characters");
    }

    @Test
    void updateRejectsNonPositiveRequestsPerWindow() throws Exception {
        assertInvalid("requestsPerWindow", "0", "requestsPerWindow must be greater than 0");
    }

    @Test
    void updateRejectsNonPositiveWindowSeconds() throws Exception {
        assertInvalid("windowSeconds", "-1", "windowSeconds must be greater than 0");
    }

    @Test
    void updateRejectsNegativePrice() throws Exception {
        assertInvalid("priceCentsPerMonth", "-1", "priceCentsPerMonth must not be negative");
    }

    @Test
    void updateReturns404WhenTierDoesNotExist() throws Exception {
        when(subscriptionTierService.update(eq(999L), any(UpdateSubscriptionTierRequest.class)))
                .thenThrow(new SubscriptionTierNotFoundException(999L));

        mockMvc.perform(patch("/subscription-tiers/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Gold\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/subscription-tiers/999"));
    }

    @Test
    void updateReturns409WhenTierNameAlreadyExists() throws Exception {
        when(subscriptionTierService.update(eq(7L), any(UpdateSubscriptionTierRequest.class)))
                .thenThrow(new SubscriptionTierAlreadyExistsException("Silver"));

        mockMvc.perform(patch("/subscription-tiers/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Silver\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_ALREADY_EXISTS"))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .doesNotContain("DataIntegrityViolation")
                        .doesNotContain("unique constraint"));
    }

    @Test
    void deleteReturns204WithNoBody() throws Exception {
        mockMvc.perform(delete("/subscription-tiers/7"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(subscriptionTierService).delete(7L);
    }

    @Test
    void deleteReturns404WhenTierDoesNotExist() throws Exception {
        doThrow(new SubscriptionTierNotFoundException(999L)).when(subscriptionTierService).delete(999L);

        mockMvc.perform(delete("/subscription-tiers/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/subscription-tiers/999"));
    }

    @Test
    void deleteReturns409WhenTierIsReferenced() throws Exception {
        doThrow(new SubscriptionTierInUseException(7L)).when(subscriptionTierService).delete(7L);

        mockMvc.perform(delete("/subscription-tiers/7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_IN_USE"))
                .andExpect(jsonPath("$.message").value(
                        "Subscription tier with id 7 is referenced by one or more subscriptions"))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .doesNotContain("foreign key")
                        .doesNotContain("DataIntegrityViolation"));
    }

    @Test
    void updateStatusReturns200WithUpdatedStatus() throws Exception {
        when(subscriptionTierService.changeStatus(eq(7L), any(UpdateSubscriptionTierStatusRequest.class)))
                .thenReturn(new SubscriptionTierResponse(
                        7L, "Premium", "Premium access", 500, 30, 0, SubscriptionTierStatus.INACTIVE,
                        TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/subscription-tiers/7/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "INACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Premium"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        verify(subscriptionTierService)
                .changeStatus(eq(7L), eq(new UpdateSubscriptionTierStatusRequest(SubscriptionTierStatus.INACTIVE)));
    }

    @Test
    void updateStatusRejectsMissingStatusWith400() throws Exception {
        mockMvc.perform(patch("/subscription-tiers/7/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("status is required"));

        verify(subscriptionTierService, never()).changeStatus(any(), any());
    }

    @Test
    void updateStatusRejectsInvalidStatusValueWith400() throws Exception {
        mockMvc.perform(patch("/subscription-tiers/7/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "PAUSED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("invalid value"));

        verify(subscriptionTierService, never()).changeStatus(any(), any());
    }

    @Test
    void updateStatusReturns404WhenTierDoesNotExist() throws Exception {
        when(subscriptionTierService.changeStatus(eq(999L), any(UpdateSubscriptionTierStatusRequest.class)))
                .thenThrow(new SubscriptionTierNotFoundException(999L));

        mockMvc.perform(patch("/subscription-tiers/999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "INACTIVE"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_TIER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/subscription-tiers/999/status"))
                .andExpect(jsonPath("$.message").value("Subscription tier with id 999 does not exist"));
    }

    private void assertInvalid(String field, String value, String message) throws Exception {
        mockMvc.perform(patch("/subscription-tiers/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + field + "\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors." + field).value(message));

        verify(subscriptionTierService, never()).update(any(), any());
    }
}
