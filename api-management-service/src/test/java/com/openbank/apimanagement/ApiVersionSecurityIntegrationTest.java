package com.openbank.apimanagement;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.apimanagement.api.Api;
import com.openbank.apimanagement.api.ApiRepository;
import com.openbank.apimanagement.api.ApiVersionRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class ApiVersionSecurityIntegrationTest {

    private static final String TEST_JWT_SECRET = "integration-test-jwt-secret-value-0123456789-ab";
    private static final AtomicLong SEQUENCE = new AtomicLong(0);

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
    private ApiRepository apiRepository;

    @Autowired
    private ApiVersionRepository apiVersionRepository;

    @Test
    void postVersionWithoutTokenReturns401() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("v1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("at com.openbank")
                            .doesNotContain("java.lang")
                            .doesNotContain(TEST_JWT_SECRET);
                });
    }

    @Test
    void postVersionWithInvalidTokenReturns401() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer not.a.valid.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("v1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void postVersionWithExpiredTokenReturns401() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + token("999", "ADMIN", -3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("v1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void postVersionWithTokenMissingRoleClaimReturns401() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + tokenWithoutRole("100"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("v1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void adminCanCreateListAndGetVersions() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("v1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("v1"));

        mockMvc.perform(get("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("v1"));

        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(apiId).get(0).getId();
        mockMvc.perform(get("/apis/" + apiId + "/versions/" + versionId)
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiId").value(apiId));
    }

    @Test
    void developerCanCreateListAndGetVersions() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("v1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("v1"));

        mockMvc.perform(get("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("v1"));

        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(apiId).get(0).getId();
        mockMvc.perform(get("/apis/" + apiId + "/versions/" + versionId)
                        .header("Authorization", "Bearer " + token("42", "DEVELOPER", 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiId").value(apiId));
    }

    @Test
    void unauthenticatedListAndGetReturn401() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(get("/apis/" + apiId + "/versions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/apis/" + apiId + "/versions/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tokenWithUnknownRoleIsRejectedAsUnauthorizedNotAuthorized() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(get("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + token("77", "CUSTOMER", 3600)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("at com.openbank")
                            .doesNotContain(TEST_JWT_SECRET)
                            .doesNotContain("CUSTOMER");
                });
    }

    private Long createApi() throws Exception {
        String contextPath = "/version-sec-" + SEQUENCE.incrementAndGet();
        mockMvc.perform(post("/apis")
                        .header("Authorization", "Bearer " + token("1", "ADMIN", 3600))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(apiBody(contextPath)))
                .andExpect(status().isCreated());
        return apiRepository.findByContextPath(contextPath)
                .map(Api::getId)
                .orElseThrow();
    }

    private String apiBody(String contextPath) {
        return """
                {
                  "name": "Version Security API",
                  "description": "Api used for version security tests",
                  "contextPath": "%s"
                }
                """.formatted(contextPath);
    }

    private String versionBody(String version) {
        return """
                {
                  "version": "%s"
                }
                """.formatted(version);
    }

    private String tokenWithoutRole(String subject) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                claims);
        jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
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