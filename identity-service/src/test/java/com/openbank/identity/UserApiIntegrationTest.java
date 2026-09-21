package com.openbank.identity;

import com.openbank.identity.user.User;
import com.openbank.identity.user.UserRepository;
import com.openbank.identity.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class UserApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createHashesPasswordThenGetNeverReturnsSecrets() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "roundtrip@example.com",
                                  "password": "SuperSecret!123",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("roundtrip@example.com"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User stored = userRepository.findByEmail("roundtrip@example.com").orElseThrow();
        String storedHash = stored.getPasswordHash();

        assertThat(storedHash)
                .as("the stored value must be a BCrypt hash, never the plaintext password")
                .isNotEqualTo("SuperSecret!123")
                .startsWith("$2");
        assertThat(passwordEncoder.matches("SuperSecret!123", storedHash))
                .as("the plaintext password must verify against the stored hash")
                .isTrue();
        assertThat(passwordEncoder.matches("WrongPassword", storedHash)).isFalse();

        mockMvc.perform(get("/users/{id}", stored.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(stored.getId()))
                .andExpect(jsonPath("$.email").value("roundtrip@example.com"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .as("response must not contain the plaintext password or the stored hash")
                            .doesNotContain("SuperSecret!123")
                            .doesNotContain(storedHash);
                });

        assertThat(userRepository.findById(stored.getId()).orElseThrow().getPasswordHash())
                .isEqualTo(storedHash);
    }

    @Test
    void createRejectsDuplicateEmailWith409() throws Exception {
        String body = """
                {
                  "email": "duplicate@example.com",
                  "password": "some-password",
                  "role": "DEVELOPER"
                }
                """;
        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void getReturns404ForNonexistentUser() throws Exception {
        mockMvc.perform(get("/users/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void createRejectsMissingPasswordWith400() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "nopass@example.com",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void createRejectsBlankPasswordAndMissingEmailWith400() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "   ",
                                  "password": "   ",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void createRejectsInvalidRoleWith400AndIdentifiesField() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "bogusrole@example.com",
                                  "password": "some-password",
                                  "role": "SUPERUSER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/users"))
                .andExpect(jsonPath("$.fieldErrors.role").exists())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .doesNotContain("SUPERUSER")
                            .doesNotContain("InvalidFormatException")
                            .doesNotContain("com.fasterxml");
                });
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
                    org.assertj.core.api.Assertions.assertThat(body)
                            .doesNotContain("Unexpected character")
                            .doesNotContain("JsonParseException")
                            .doesNotContain("com.fasterxml")
                            .doesNotContain("\tat ")
                            .doesNotContain("java.lang");
                });
    }

    @Test
    void createRejectsMissingRoleWith400AndIdentifiesField() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "norole@example.com",
                                  "password": "some-password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.role").exists());
    }

    @Test
    void unknownRouteReturns404Not500() throws Exception {
        mockMvc.perform(get("/definitely/not/a/route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void repositoryPersistsAllFields() {
        User saved = userRepository.save(new User("repo@example.com", "some-hash", UserRole.ADMIN));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEmail()).isEqualTo("repo@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("some-hash");
        assertThat(saved.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(saved.getCreatedAt()).isNotNull();
    }
}