package com.openbank.analytics.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class AnalyticsJwtSecurityIntegrationTest {

    private static final String TEST_JWT_SECRET = "integration-test-jwt-secret-value-0123456789-ab";
    private static final String OTHER_JWT_SECRET = "a-completely-different-secret-value-9876543210-zyxw";
    private static final Clock CLOCK = Clock.systemUTC();

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
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.execute("DELETE FROM runtime_analytics_events");
    }

    @Test
    void noJwtReturns401() throws Exception {
        mockMvc.perform(get("/analytics/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("at com.openbank")
                            .doesNotContain("java.lang")
                            .doesNotContain("InvalidJwtException")
                            .doesNotContain(TEST_JWT_SECRET);
                });
    }

    @Test
    void adminJwtReturns200() throws Exception {
        mockMvc.perform(get("/analytics/events")
                        .header("Authorization", "Bearer " + token("7", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0]").doesNotExist());
    }

    @Test
    void developerJwtReturns200() throws Exception {
        mockMvc.perform(get("/analytics/events")
                        .header("Authorization", "Bearer " + token("9", "DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void jwtWithUnknownRoleReturns403() throws Exception {
        mockMvc.perform(get("/analytics/events")
                        .header("Authorization", "Bearer " + token("7", "CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("at com.openbank")
                            .doesNotContain("java.lang")
                            .doesNotContain(TEST_JWT_SECRET);
                });
    }

    @Test
    void malformedJwtReturns401() throws Exception {
        mockMvc.perform(get("/analytics/events")
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void malformedBearerHeaderReturns401() throws Exception {
        mockMvc.perform(get("/analytics/events").header("Authorization", "Bearer"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/analytics/events").header("Authorization", "no-bearer-format"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tamperedJwtReturns401() throws Exception {
        String token = token("7", "ADMIN");
        String[] parts = token.split("\\.");
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? "B" : "A") + parts[2].substring(1);

        mockMvc.perform(get("/analytics/events")
                        .header("Authorization", "Bearer " + String.join(".", parts)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void jwtSignedWithWrongSecretReturns401() throws Exception {
        mockMvc.perform(get("/analytics/events")
                        .header("Authorization", "Bearer " + tokenWithSecret("7", "ADMIN", OTHER_JWT_SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void actuatorHealthRemainsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownRouteIsNotPublic() throws Exception {
        mockMvc.perform(get("/analytics/unknown"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/analytics/unknown")
                        .header("Authorization", "Bearer " + token("7", "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void internalIngestionStillRejectsMissingTokenWith401() throws Exception {
        mockMvc.perform(post("/internal/analytics/events")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalIngestionDoesNotAcceptUserJwtAsItsOwnToken() throws Exception {
        mockMvc.perform(post("/internal/analytics/events")
                        .header("Authorization", "Bearer " + token("7", "ADMIN"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private static String token(String subject, String role) throws Exception {
        return tokenWithSecret(subject, role, TEST_JWT_SECRET);
    }

    private static String tokenWithSecret(String subject, String role, String secret) throws Exception {
        Instant now = CLOCK.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}