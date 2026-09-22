package com.openbank.apimanagement.api;

import com.openbank.apimanagement.exception.ApiNotFoundException;
import com.openbank.apimanagement.exception.ApiVersionAlreadyExistsException;
import com.openbank.apimanagement.exception.ApiVersionNotFoundException;
import com.openbank.apimanagement.exception.InvalidLifecycleTransitionException;
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

@WebMvcTest(ApiVersionController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApiVersionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiVersionService apiVersionService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    private static final String VALID_BODY = """
            {
              "version": "v1"
            }
            """;

    @Test
    void createReturns201WithBodyAndLocationHeader() throws Exception {
        when(apiVersionService.create(eq(1L), any(CreateApiVersionRequest.class)))
                .thenReturn(new ApiVersionResponse(1L, 1L, "v1", ApiVersionLifecycle.CREATED, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/apis/1/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/apis/1/versions/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.apiId").value(1))
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.lifecycle").value("CREATED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.publishedAt").doesNotExist());
    }

    @Test
    void createRejectsMissingVersionWith400() throws Exception {
        mockMvc.perform(post("/apis/1/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "wrong field name"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.version").value("version is required"));
    }

    @Test
    void createRejectsWhitespaceOnlyVersionWith400() throws Exception {
        mockMvc.perform(post("/apis/1/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.version").exists());
    }

    @Test
    void createRejectsVersionWithLeadingOrTrailingWhitespaceWith400() throws Exception {
        mockMvc.perform(post("/apis/1/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": "  v1 "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.version").value("version must not contain leading or trailing whitespace"));
    }

    @Test
    void createRejectsVersionExceedingMaxLengthWith400() throws Exception {
        mockMvc.perform(post("/apis/1/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": "0123456789012345678901234567890123456789012345678901234567890123456789"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.version").value("version must not exceed 64 characters"));
    }

    @Test
    void createReturns404WhenApiDoesNotExist() throws Exception {
        when(apiVersionService.create(eq(999L), any(CreateApiVersionRequest.class)))
                .thenThrow(new ApiNotFoundException(999L));

        mockMvc.perform(post("/apis/999/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/apis/999/versions"));
    }

    @Test
    void createReturns409WhenVersionAlreadyExistsForApi() throws Exception {
        when(apiVersionService.create(eq(1L), any(CreateApiVersionRequest.class)))
                .thenThrow(new ApiVersionAlreadyExistsException(1L, "v1"));

        mockMvc.perform(post("/apis/1/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("API_VERSION_ALREADY_EXISTS"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("unique constraint")
                            .doesNotContain("DataIntegrityViolation")
                            .doesNotContain("at com.openbank");
                });
    }

    @Test
    void getReturns200WithVersionDetails() throws Exception {
        when(apiVersionService.get(eq(1L), eq(5L)))
                .thenReturn(new ApiVersionResponse(5L, 1L, "v1", ApiVersionLifecycle.PUBLISHED, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(get("/apis/1/versions/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.apiId").value(1))
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.lifecycle").value("PUBLISHED"));
    }

    @Test
    void getReturnsStructured404WhenApiDoesNotExist() throws Exception {
        when(apiVersionService.get(eq(999L), eq(5L))).thenThrow(new ApiNotFoundException(999L));

        mockMvc.perform(get("/apis/999/versions/5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Api with id 999 does not exist"));
    }

    @Test
    void getReturnsStructured404WhenVersionDoesNotExist() throws Exception {
        when(apiVersionService.get(eq(1L), eq(404L))).thenThrow(new ApiVersionNotFoundException(1L, 404L));

        mockMvc.perform(get("/apis/1/versions/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_VERSION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Api version with id 404 does not exist for api 1"));
    }

    @Test
    void getReturnsStructured404WhenVersionBelongsToAnotherApi() throws Exception {
        when(apiVersionService.get(eq(1L), eq(5L))).thenThrow(new ApiVersionNotFoundException(1L, 5L));

        mockMvc.perform(get("/apis/1/versions/5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_VERSION_NOT_FOUND"));
    }

    @Test
    void listReturns200WithVersions() throws Exception {
        when(apiVersionService.list(eq(1L)))
                .thenReturn(List.of(
                        new ApiVersionResponse(1L, 1L, "v1", ApiVersionLifecycle.CREATED, TIMESTAMP, TIMESTAMP),
                        new ApiVersionResponse(2L, 1L, "v2", ApiVersionLifecycle.DEPRECATED, TIMESTAMP, TIMESTAMP)));

        mockMvc.perform(get("/apis/1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].version").value("v1"))
                .andExpect(jsonPath("$[0].lifecycle").value("CREATED"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].version").value("v2"))
                .andExpect(jsonPath("$[1].lifecycle").value("DEPRECATED"))
                .andExpect(jsonPath("$[0].api").doesNotExist());
    }

    @Test
    void listReturns404WhenApiDoesNotExist() throws Exception {
        when(apiVersionService.list(eq(999L))).thenThrow(new ApiNotFoundException(999L));

        mockMvc.perform(get("/apis/999/versions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"));
    }

    @Test
    void changeLifecycleReturns200WithUpdatedVersion() throws Exception {
        when(apiVersionService.changeLifecycle(eq(1L), eq(5L), any(UpdateApiVersionLifecycleRequest.class)))
                .thenReturn(new ApiVersionResponse(5L, 1L, "v1", ApiVersionLifecycle.PUBLISHED, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/apis/1/versions/5/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lifecycle": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.apiId").value(1))
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.lifecycle").value("PUBLISHED"));
    }

    @Test
    void changeLifecycleRejectsMissingLifecycleWith400() throws Exception {
        mockMvc.perform(patch("/apis/1/versions/5/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.lifecycle").value("lifecycle is required"));
    }

    @Test
    void changeLifecycleRejectsInvalidLifecycleValueWith400() throws Exception {
        mockMvc.perform(patch("/apis/1/versions/5/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lifecycle": "NOT_A_LIFECYCLE"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.lifecycle").value("invalid value"));
    }

    @Test
    void changeLifecycleRejectsMalformedJsonWith400() throws Exception {
        mockMvc.perform(patch("/apis/1/versions/5/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void changeLifecycleReturns404WhenApiDoesNotExist() throws Exception {
        when(apiVersionService.changeLifecycle(eq(999L), eq(5L), any(UpdateApiVersionLifecycleRequest.class)))
                .thenThrow(new ApiNotFoundException(999L));

        mockMvc.perform(patch("/apis/999/versions/5/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lifecycle": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"));
    }

    @Test
    void changeLifecycleReturns404WhenVersionDoesNotExist() throws Exception {
        when(apiVersionService.changeLifecycle(eq(1L), eq(404L), any(UpdateApiVersionLifecycleRequest.class)))
                .thenThrow(new ApiVersionNotFoundException(1L, 404L));

        mockMvc.perform(patch("/apis/1/versions/404/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lifecycle": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_VERSION_NOT_FOUND"));
    }

    @Test
    void changeLifecycleReturns409WhenTransitionIsInvalid() throws Exception {
        when(apiVersionService.changeLifecycle(eq(1L), eq(5L), any(UpdateApiVersionLifecycleRequest.class)))
                .thenThrow(new InvalidLifecycleTransitionException(ApiVersionLifecycle.PUBLISHED, ApiVersionLifecycle.CREATED));

        mockMvc.perform(patch("/apis/1/versions/5/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lifecycle": "CREATED"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_LIFECYCLE_TRANSITION"))
                .andExpect(jsonPath("$.message").value("Lifecycle transition from PUBLISHED to CREATED is not allowed"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("at com.openbank")
                            .doesNotContain("exception");
                });
    }

    @Test
    void getWithNonNumericVersionIdReturns400TypeMismatch() throws Exception {
        mockMvc.perform(get("/apis/1/versions/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.fieldErrors.versionId").value("invalid value"));
    }
}