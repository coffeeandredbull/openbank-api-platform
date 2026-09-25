package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.exception.SubscriptionTierAlreadyExistsException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    void updateReturns200WithPublicTierFields() throws Exception {
        when(subscriptionTierService.update(eq(7L), any(UpdateSubscriptionTierRequest.class)))
                .thenReturn(new SubscriptionTierResponse(7L, "Premium", "Premium access", 500, 30, TIMESTAMP, TIMESTAMP));

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
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());
    }

    @Test
    void updateForwardsOmittedFieldsAsNull() throws Exception {
        when(subscriptionTierService.update(eq(7L), any(UpdateSubscriptionTierRequest.class)))
                .thenReturn(new SubscriptionTierResponse(7L, "Gold", "Description", 500, 60, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/subscription-tiers/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestsPerWindow": 500
                                }
                                """))
                .andExpect(status().isOk());

        verify(subscriptionTierService).update(eq(7L), eq(new UpdateSubscriptionTierRequest(null, null, 500, null)));
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
