package com.openbank.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.identity.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class SecurityRbacIntegrationTest {

    private static final String TEST_JWT_SECRET = "integration-test-jwt-secret-value-0123456789-ab";
    private static final AtomicLong EMAIL_SEQUENCE = new AtomicLong(0);
    private static final String PASSWORD = "Correct-Horse-42";

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
    private ObjectMapper objectMapper;

    @Test
    void registrationRemainsPublicWithoutJwt() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userBody(uniqueEmail("public-reg"), UserRole.DEVELOPER)))
                .andExpect(status().isCreated());
    }

    @Test
    void loginRemainsPublicWithoutJwt() throws Exception {
        String email = uniqueEmail("public-login");
        register(email, UserRole.DEVELOPER);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void authenticatedEndpointWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/test/authenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .doesNotContain("at com.openbank")
                            .doesNotContain("java.lang")
                            .doesNotContain("InvalidJwtException")
                            .doesNotContain(TEST_JWT_SECRET);
                });
    }

    @Test
    void authenticatedEndpointWithInvalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/test/authenticated")
                        .header("Authorization", "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void authenticatedEndpointWithExpiredTokenReturns401() throws Exception {
        mockMvc.perform(get("/test/authenticated")
                        .header("Authorization", "Bearer " + expiredToken("1", "DEVELOPER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void authenticatedEndpointWithDeveloperTokenReturns200() throws Exception {
        String token = registerAndLogin(UserRole.DEVELOPER);

        mockMvc.perform(get("/test/authenticated")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void authenticatedEndpointWithAdminTokenReturns200() throws Exception {
        String token = registerAndLogin(UserRole.ADMIN);

        mockMvc.perform(get("/test/authenticated")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void adminEndpointWithDeveloperTokenReturns403() throws Exception {
        String token = registerAndLogin(UserRole.DEVELOPER);

        mockMvc.perform(get("/test/admin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .doesNotContain("at com.openbank")
                            .doesNotContain("java.lang")
                            .doesNotContain(TEST_JWT_SECRET);
                });
    }

    @Test
    void adminEndpointWithAdminTokenReturns200() throws Exception {
        String token = registerAndLogin(UserRole.ADMIN);

        mockMvc.perform(get("/test/admin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void malformedAuthorizationHeadersReturn401() throws Exception {
        String[] malformedHeaders = {"Bearer", "Bearer ", "Bearer   ", "Bearer"};

        for (String header : malformedHeaders) {
            mockMvc.perform(get("/test/authenticated")
                            .header("Authorization", header))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        mockMvc.perform(get("/test/authenticated").header("Authorization", "no-bearer-format"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void basicAuthorizationHeaderReturns401() throws Exception {
        mockMvc.perform(get("/test/authenticated")
                        .header("Authorization", "Basic Zm9vOmJhcg=="))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tamperedTokenReturns401() throws Exception {
        String token = registerAndLogin(UserRole.DEVELOPER);
        String[] parts = token.split("\\.");
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? "B" : "A") + parts[2].substring(1);

        mockMvc.perform(get("/test/authenticated")
                        .header("Authorization", "Bearer " + String.join(".", parts)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private String registerAndLogin(UserRole role) throws Exception {
        String email = uniqueEmail("rbac");
        register(email, role);
        return login(email);
    }

    private void register(String email, UserRole role) throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userBody(email, role)))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + EMAIL_SEQUENCE.incrementAndGet() + "@example.com";
    }

    private String userBody(String email, UserRole role) {
        return """
                {
                  "email": "%s",
                  "password": "%s",
                  "role": "%s"
                }
                """.formatted(email, PASSWORD, role.name());
    }

    private String loginBody(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);
    }

    private String expiredToken(String subject, String role) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("role", role)
                .issueTime(Date.from(now.minusSeconds(7200)))
                .expirationTime(Date.from(now.minusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                claims);
        jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}