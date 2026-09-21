package com.openbank.identity.user;

import com.openbank.identity.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    private static final String VALID_BODY = """
            {
              "email": "dev@example.com",
              "password": "SuperSecret!123",
              "role": "DEVELOPER"
            }
            """;

    @Test
    void createReturns201AndNeverReturnsPasswordOrHash() throws Exception {
        when(userService.create(any(CreateUserRequest.class)))
                .thenReturn(new UserResponse(1L, "dev@example.com", UserRole.DEVELOPER,
                        Instant.parse("2026-09-21T10:00:00Z")));

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("dev@example.com"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .as("response body must never contain the password or its hash")
                            .doesNotContain("SuperSecret!123")
                            .doesNotContain("passwordHash");
                });
    }

    @Test
    void createRejectsMissingEmailWith400AndIdentifiesField() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "password": "some-password",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(standardErrorStructure())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").value("email is required"));

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void createRejectsBlankEmailWith400AndIdentifiesField() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "   ",
                                  "password": "some-password",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void createRejectsMissingPasswordWith400AndIdentifiesField() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dev@example.com",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.password").value("password is required"));

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void createRejectsBlankPasswordWith400AndIdentifiesField() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dev@example.com",
                                  "password": "   ",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.password").value("password is required"));

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void createRejectsMissingRoleWith400AndIdentifiesField() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dev@example.com",
                                  "password": "some-password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.role").value("role is required"));

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void createRejectsInvalidRoleValueWith400AndIdentifiesField() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dev@example.com",
                                  "password": "some-password",
                                  "role": "SUPERUSER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.role").exists())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("SUPERUSER")
                            .doesNotContain("InvalidFormatException")
                            .doesNotContain("com.fasterxml");
                });

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void createRejectsEmptyJsonObjectWith400ForAllMissingFields() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.role").exists());

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void createRejectsMalformedJsonWith400AndSafeMessage() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "broken@example.com",
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .as("Jackson/parser internals must not leak")
                            .doesNotContain("Unexpected character")
                            .doesNotContain("JsonParseException")
                            .doesNotContain("com.fasterxml")
                            .doesNotContain("\tat ")
                            .doesNotContain("java.lang");
                });

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void createRejectsUnsupportedContentTypeWith415() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(VALID_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void getWithNonNumericIdReturns400() throws Exception {
        mockMvc.perform(get("/users/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.fieldErrors.id").exists());
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(patch("/users"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unknownRouteReturns404() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void getReturns200WithUserWithoutPasswordHash() throws Exception {
        when(userService.get(eq(1L)))
                .thenReturn(new UserResponse(1L, "dev@example.com", UserRole.DEVELOPER,
                        Instant.parse("2026-09-21T10:00:00Z")));

        mockMvc.perform(get("/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("dev@example.com"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void getReturnsStructured404WhenUserDoesNotExist() throws Exception {
        when(userService.get(eq(999L))).thenThrow(new UserNotFoundException(999L));

        mockMvc.perform(get("/users/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/users/999"))
                .andExpect(jsonPath("$.message").value("User with id 999 does not exist"));
    }

    private static org.springframework.test.web.servlet.ResultMatcher standardErrorStructure() {
        return result -> {
            String body = result.getResponse().getContentAsString();
            assertThat(body.contains("\"timestamp\"")).isTrue();
            assertThat(body.contains("\"status\"")).isTrue();
            assertThat(body.contains("\"error\"")).isTrue();
            assertThat(body.contains("\"path\"")).isTrue();
            assertThat(body.contains("\"code\"")).isTrue();
            assertThat(body.contains("\"message\"")).isTrue();
            assertThat(body.contains("\"fieldErrors\"")).isTrue();
        };
    }
}