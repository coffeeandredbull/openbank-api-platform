package com.openbank.apimanagement;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class SubscriptionTierSecurityIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM subscriptions");
        jdbcTemplate.execute("DELETE FROM subscription_tiers");
    }

    @Test
    void adminCanCreateAndListSubscriptionTiers() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Gold",
                                  "description": "Premium access"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Gold"));

        mockMvc.perform(get("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Gold"));
    }

    @Test
    void developerCannotCreateSubscriptionTiers() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Gold"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThatNoTiersPersisted();
    }

    @Test
    void developerCannotListSubscriptionTiers() throws Exception {
        mockMvc.perform(get("/subscription-tiers")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void unauthenticatedCreateAndListReturn401() throws Exception {
        mockMvc.perform(post("/subscription-tiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Gold"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/subscription-tiers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThatNoTiersPersisted();
    }

    private void assertThatNoTiersPersisted() {
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from subscription_tiers", Long.class)).isZero();
    }

    private String token(String subject, String role, long expiresInSeconds) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                claims);
        jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
