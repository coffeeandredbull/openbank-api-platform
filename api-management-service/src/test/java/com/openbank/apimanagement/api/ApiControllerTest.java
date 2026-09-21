package com.openbank.apimanagement.api;

import com.openbank.apimanagement.exception.ApiNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiService apiService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    private static final String VALID_BODY = """
            {
              "name": "Payments API",
              "description": "Bank payment operations",
              "contextPath": "/payments"
            }
            """;

    @Test
    void createReturns201WithBodyAndLocationHeader() throws Exception {
        when(apiService.create(any(CreateApiRequest.class)))
                .thenReturn(new ApiResponse(1L, "Payments API", "Bank payment operations", "/payments", TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/apis/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Payments API"))
                .andExpect(jsonPath("$.description").value("Bank payment operations"))
                .andExpect(jsonPath("$.contextPath").value("/payments"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void createRejectsMissingAndInvalidFieldsWith400() throws Exception {
        mockMvc.perform(post("/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "No name or context path here"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").value("name is required"))
                .andExpect(jsonPath("$.fieldErrors.contextPath").value("contextPath is required"));
    }

    @Test
    void createRejectsInvalidContextPathWith400() throws Exception {
        mockMvc.perform(post("/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Payments API",
                                  "description": "Bank payment operations",
                                  "contextPath": "/payments?x=1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.contextPath").exists());
    }

    @Test
    void getReturns200WithApiDetails() throws Exception {
        when(apiService.get(eq(1L)))
                .thenReturn(new ApiResponse(1L, "Payments API", "Bank payment operations", "/payments", TIMESTAMP, TIMESTAMP));

        mockMvc.perform(get("/apis/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Payments API"))
                .andExpect(jsonPath("$.contextPath").value("/payments"));
    }

    @Test
    void getReturnsStructured404WhenApiDoesNotExist() throws Exception {
        when(apiService.get(eq(999L))).thenThrow(new ApiNotFoundException(999L));

        mockMvc.perform(get("/apis/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/apis/999"))
                .andExpect(jsonPath("$.message").value("Api with id 999 does not exist"));
    }

    @Test
    void listReturns200WithAllApis() throws Exception {
        when(apiService.list())
                .thenReturn(List.of(
                        new ApiResponse(1L, "Payments API", "Bank payment operations", "/payments", TIMESTAMP, TIMESTAMP),
                        new ApiResponse(2L, "Accounts API", "Bank account operations", "/accounts", TIMESTAMP, TIMESTAMP)));

        mockMvc.perform(get("/apis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].contextPath").value("/payments"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].contextPath").value("/accounts"))
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    void createRejectsMalformedJsonWith400AndSafeMessage() throws Exception {
        mockMvc.perform(post("/apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Payments API",
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("Unexpected character")
                            .doesNotContain("JsonParseException")
                            .doesNotContain("com.fasterxml")
                            .doesNotContain("\tat ")
                            .doesNotContain("java.lang");
                });
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(patch("/apis"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unknownRouteReturns404() throws Exception {
        mockMvc.perform(get("/apis/1/versions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}