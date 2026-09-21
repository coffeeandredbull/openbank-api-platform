package com.openbank.identity.user;

import com.openbank.identity.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void createReturns201WithUserAndNeverIncludesPasswordHash() throws Exception {
        when(userService.create(any(CreateUserRequest.class)))
                .thenReturn(new UserResponse(1L, "dev@example.com", UserRole.DEVELOPER,
                        Instant.parse("2026-09-21T10:00:00Z")));

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dev@example.com",
                                  "passwordHash": "secret-hash-value",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("dev@example.com"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password_passwordHash").doesNotExist())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .as("response body must never contain the password hash")
                            .doesNotContain("secret-hash-value")
                            .doesNotContain("passwordHash");
                });
    }

    @Test
    void createRejectsMissingEmailWith400() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "passwordHash": "some-hash",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void createRejectsBlankPasswordHashWith400() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dev@example.com",
                                  "passwordHash": "   ",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(userService, never()).create(any(CreateUserRequest.class));
    }

    @Test
    void createRejectsUnknownRoleWith400() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dev@example.com",
                                  "passwordHash": "some-hash",
                                  "role": "SUPERUSER"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(userService, never()).create(any(CreateUserRequest.class));
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
    void getReturns404WhenUserDoesNotExist() throws Exception {
        when(userService.get(eq(999L))).thenThrow(new UserNotFoundException(999L));

        mockMvc.perform(get("/users/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }
}