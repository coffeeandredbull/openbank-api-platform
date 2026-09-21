package com.openbank.apimanagement;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.apimanagement.api.Api;
import com.openbank.apimanagement.api.ApiRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class ApiCatalogIntegrationTest {

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

    @Test
    void createPersistsApiWithGeneratedIdAndTimestamps() throws Exception {
        String contextPath = "/payments-" + SEQUENCE.incrementAndGet();

        mockMvc.perform(post("/apis")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Payments API", "Bank payment operations", contextPath)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Payments API"))
                .andExpect(jsonPath("$.description").value("Bank payment operations"))
                .andExpect(jsonPath("$.contextPath").value(contextPath))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(r -> {
                    String createdAt = objectMapperPath(r, "$.createdAt");
                    String updatedAt = objectMapperPath(r, "$.updatedAt");
                    assertThat(createdAt).isEqualTo(updatedAt);
                });

        assertThat(apiRepository.findByContextPath(contextPath)).isPresent();
    }

    @Test
    void duplicateContextPathReturns409AndKeepsSingleRow() throws Exception {
        String contextPath = "/accounts-" + SEQUENCE.incrementAndGet();
        assertThat(postApi(contextPath)).isEqualTo(201);

        mockMvc.perform(post("/apis")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Accounts API", "Bank account operations", contextPath)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTEXT_PATH_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(r -> {
                    String responseBody = r.getResponse().getContentAsString();
                    assertThat(responseBody)
                            .doesNotContain("unique constraint")
                            .doesNotContain("SQL")
                            .doesNotContain("DataIntegrityViolation")
                            .doesNotContain("at com.openbank");
                });

        long count = apiRepository.findAll().stream()
                .filter(api -> api.getContextPath().equals(contextPath))
                .count();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void contextPathUniquenessEnforcedByDatabase() {
        Api first = new Api("Customer API", "Customer operations", "/customer-api");
        apiRepository.saveAndFlush(first);

        Api duplicate = new Api("Customer API 2", "Customer operations 2", "/customer-api");

        assertThatThrownBy(() -> apiRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void getExistingApiReturns200WithDetails() throws Exception {
        String contextPath = "/transactions-" + SEQUENCE.incrementAndGet();
        assertThat(postApi(contextPath)).isEqualTo(201);
        long id = apiRepository.findByContextPath(contextPath).map(Api::getId).orElseThrow();

        mockMvc.perform(get("/apis/" + id)
                        .header("Authorization", "Bearer " + developerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Payments API"))
                .andExpect(jsonPath("$.contextPath").value(contextPath));
    }

    @Test
    void getNonexistentApiReturns404WithStructuredSafeBody() throws Exception {
        mockMvc.perform(get("/apis/98765")
                        .header("Authorization", "Bearer " + developerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Api with id 98765 does not exist"))
                .andExpect(r -> {
                    String responseBody = r.getResponse().getContentAsString();
                    assertThat(responseBody)
                            .doesNotContain("at com.openbank")
                            .doesNotContain("java.lang")
                            .doesNotContain("NoSuchElement");
                });
    }

    @Test
    void listReturnsAllApisInCreationOrder() throws Exception {
        String pathA = "/list-a-" + SEQUENCE.incrementAndGet();
        String pathB = "/list-b-" + SEQUENCE.incrementAndGet();
        assertThat(postApi(pathA)).isEqualTo(201);
        assertThat(postApi(pathB)).isEqualTo(201);

        mockMvc.perform(get("/apis")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].contextPath").value(org.hamcrest.Matchers.hasItems(pathA, pathB)))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    int indexA = body.indexOf("\"" + pathA + "\"");
                    int indexB = body.indexOf("\"" + pathB + "\"");
                    assertThat(indexA).isLessThan(indexB);
                    assertThat(body)
                            .doesNotContain("hibernate")
                            .doesNotContain("password")
                            .doesNotContain("secret");
                });
    }

    @Test
    void invalidApiReturned400ValidationFailed() throws Exception {
        mockMvc.perform(post("/apis")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "missing name and context path"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").value("name is required"))
                .andExpect(jsonPath("$.fieldErrors.contextPath").value("contextPath is required"));
    }

    @Test
    void invalidContextPathReturned400() throws Exception {
        mockMvc.perform(post("/apis")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Broken API",
                                  "description": "invalid context path",
                                  "contextPath": "payments"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.contextPath").exists());
    }

    private int postApi(String contextPath) throws Exception {
        return mockMvc.perform(post("/apis")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Payments API", "Bank payment operations", contextPath)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String objectMapperPath(org.springframework.test.web.servlet.MvcResult r, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), path);
    }

    private String body(String name, String description, String contextPath) {
        return """
                {
                  "name": "%s",
                  "description": "%s",
                  "contextPath": "%s"
                }
                """.formatted(name, description, contextPath);
    }

    private String adminToken() throws Exception {
        return token("1", "ADMIN", 3600);
    }

    private String developerToken() throws Exception {
        return token("42", "DEVELOPER", 3600);
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