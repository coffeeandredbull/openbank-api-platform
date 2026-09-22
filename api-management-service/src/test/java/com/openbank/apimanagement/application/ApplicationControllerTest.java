package com.openbank.apimanagement.application;

import com.openbank.apimanagement.auth.JwtIdentity;
import com.openbank.apimanagement.auth.UserRole;
import com.openbank.apimanagement.exception.ApplicationNotFoundException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApplicationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApplicationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApplicationService applicationService;

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
        when(applicationService.create(eq(42L), any(CreateApplicationRequest.class)))
                .thenReturn(new ApplicationResponse(1L, "My App", "Demo", 42L, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "My App",
                                  "description": "Demo"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/applications/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("My App"))
                .andExpect(jsonPath("$.description").value("Demo"))
                .andExpect(jsonPath("$.ownerUserId").value(42))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void createDerivesOwnerFromAuthenticatedPrincipal() throws Exception {
        authenticate(42L, UserRole.DEVELOPER);
        when(applicationService.create(eq(42L), any(CreateApplicationRequest.class)))
                .thenReturn(new ApplicationResponse(1L, "My App", "Demo", 42L, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "My App"
                                }
                                """))
                .andExpect(status().isCreated());

        verify(applicationService).create(eq(42L), any(CreateApplicationRequest.class));
    }

    @Test
    void createRejectsMissingNameWith400() throws Exception {
        mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "no name here"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").value("name is required"));
    }

    @Test
    void createRejectsBlankNameWith400() throws Exception {
        mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .containsAnyOf(
                                    "name is required",
                                    "name must not contain leading or trailing whitespace")
                            .doesNotContain("Exception");
                });
    }

    @Test
    void createRejectsNameWithLeadingOrTrailingWhitespaceWith400() throws Exception {
        mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "  My App "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name")
                        .value("name must not contain leading or trailing whitespace"));
    }

    @Test
    void createRejectsNameExceedingMaxLengthWith400() throws Exception {
        mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s"
                                }
                                """.formatted("n".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").value("name must not exceed 255 characters"));
    }

    @Test
    void createRejectsDescriptionExceedingMaxLengthWith400() throws Exception {
        mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "My App",
                                  "description": "%s"
                                }
                                """.formatted("d".repeat(2001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.description")
                        .value("description must not exceed 2000 characters"));
    }

    @Test
    void createRejectsMalformedJsonWith400() throws Exception {
        mockMvc.perform(post("/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "My App",
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
    void getReturns200WithApplicationDetails() throws Exception {
        when(applicationService.get(1L, 42L))
                .thenReturn(new ApplicationResponse(1L, "My App", "Demo", 42L, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(get("/applications/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("My App"))
                .andExpect(jsonPath("$.ownerUserId").value(42));
    }

    @Test
    void getReturnsStructured404WhenApplicationDoesNotExist() throws Exception {
        when(applicationService.get(999L, 42L)).thenThrow(new ApplicationNotFoundException(999L));

        mockMvc.perform(get("/applications/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/applications/999"))
                .andExpect(jsonPath("$.message").value("Application with id 999 does not exist"));
    }

    @Test
    void listReturns200WithOwnedApplications() throws Exception {
        when(applicationService.list(42L))
                .thenReturn(List.of(
                        new ApplicationResponse(1L, "App One", "Demo", 42L, TIMESTAMP, TIMESTAMP),
                        new ApplicationResponse(2L, "App Two", "Demo", 42L, TIMESTAMP, TIMESTAMP)));

        mockMvc.perform(get("/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("App One"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].name").value("App Two"))
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    void updateReturns200WithUpdatedApplication() throws Exception {
        when(applicationService.update(eq(1L), eq(42L), any(UpdateApplicationRequest.class)))
                .thenReturn(new ApplicationResponse(1L, "New Name", "New description", 42L, TIMESTAMP, TIMESTAMP));

        mockMvc.perform(patch("/applications/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "New Name",
                                  "description": "New description"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("New Name"))
                .andExpect(jsonPath("$.description").value("New description"))
                .andExpect(jsonPath("$.ownerUserId").value(42));
    }

    @Test
    void updateRejectsBlankNameWith400() throws Exception {
        mockMvc.perform(patch("/applications/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name")
                        .value("name must not be blank or contain leading or trailing whitespace"));
    }

    @Test
    void updateReturns404WhenApplicationNotOwned() throws Exception {
        when(applicationService.update(eq(1L), eq(42L), any(UpdateApplicationRequest.class)))
                .thenThrow(new ApplicationNotFoundException(1L));

        mockMvc.perform(patch("/applications/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Hijack"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
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