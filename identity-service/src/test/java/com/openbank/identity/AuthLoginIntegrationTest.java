package com.openbank.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.identity.auth.JwtIdentity;
import com.openbank.identity.auth.JwtTokenService;
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

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class AuthLoginIntegrationTest {

    private static final String TEST_JWT_SECRET = "integration-test-jwt-secret-value-0123456789-ab";

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

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    private static final AtomicLong EMAIL_SEQUENCE = new AtomicLong(0);

    private static final String PASSWORD = "Correct-Horse-42";

    @Test
    void createThenLoginFullFlowWithRealCredentials() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userBody(email, PASSWORD)))
                .andExpect(status().isCreated());

        User stored = userRepository.findByEmail(email).orElseThrow();
        assertThat(stored.getPasswordHash())
                .isNotEqualTo(PASSWORD)
                .startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isTrue();

        String responseBody = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.userId").value(stored.getId()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseBody)
                .doesNotContain(PASSWORD)
                .doesNotContain(stored.getPasswordHash())
                .doesNotContain("passwordHash")
                .doesNotContain(TEST_JWT_SECRET);

        String accessToken = objectMapper.readTree(responseBody).get("accessToken").asText();
        JwtIdentity identity = jwtTokenService.validateToken(accessToken);
        assertThat(identity.userId()).isEqualTo(stored.getId());
        assertThat(identity.role()).isEqualTo(UserRole.DEVELOPER);
    }

    @Test
    void loginWithIncorrectPasswordReturns401() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void loginWithUnknownEmailReturns401IdenticalToWrongPassword() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        String wrongPasswordBody = performLogin(email, "wrong-password");
        String unknownEmailBody = performLogin("ghost@example.com", PASSWORD);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode wrong = mapper.readTree(wrongPasswordBody);
        JsonNode unknown = mapper.readTree(unknownEmailBody);

        assertThat(unknown.get("status")).isEqualTo(wrong.get("status"));
        assertThat(unknown.get("code")).isEqualTo(wrong.get("code"));
        assertThat(unknown.get("message")).isEqualTo(wrong.get("message"));
        assertThat(unknown.get("error")).isEqualTo(wrong.get("error"));
        assertThat(unknown.get("fieldErrors")).isEqualTo(wrong.get("fieldErrors"));
    }

    @Test
    void failedLoginsNeverRevealExistenceOrSensitiveData() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        String unknownEmailBody = performLogin("ghost@example.com", "Some-Password");

        assertThat(unknownEmailBody)
                .doesNotContain("exist")
                .doesNotContain("not found")
                .doesNotContain("ghost@example.com")
                .doesNotContain("Some-Password")
                .doesNotContain("passwordHash")
                .doesNotContain(TEST_JWT_SECRET)
                .doesNotContain("at com.openbank")
                .doesNotContain("java.lang")
                .doesNotContain("SQL")
                .doesNotContain("Hibernate");
    }

    private String uniqueEmail() {
        return "login-" + EMAIL_SEQUENCE.incrementAndGet() + "@example.com";
    }

    private void register(String email, String password) throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userBody(email, password)))
                .andExpect(status().isCreated());
    }

    private String userBody(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s",
                  "role": "DEVELOPER"
                }
                """.formatted(email, password);
    }

    private String loginBody(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);
    }

    private String performLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}