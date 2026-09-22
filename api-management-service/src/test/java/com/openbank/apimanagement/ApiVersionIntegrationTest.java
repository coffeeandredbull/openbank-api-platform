package com.openbank.apimanagement;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.apimanagement.api.Api;
import com.openbank.apimanagement.api.ApiRepository;
import com.openbank.apimanagement.api.ApiVersion;
import com.openbank.apimanagement.api.ApiVersionLifecycle;
import com.openbank.apimanagement.api.ApiVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class ApiVersionIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createVersionPersistsApiIdAndTimestamps() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("v1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.apiId").value(apiId))
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.lifecycle").value("CREATED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(r -> {
                    String createdAt = readJsonPath(r, "$.createdAt");
                    String updatedAt = readJsonPath(r, "$.updatedAt");
                    assertThat(createdAt).isEqualTo(updatedAt);
                });

        List<ApiVersion> stored = apiVersionRepository.findByApiIdOrderByIdAsc(apiId);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getApi().getId()).isEqualTo(apiId);
        assertThat(stored.get(0).getVersion()).isEqualTo("v1");
        assertThat(stored.get(0).getLifecycle()).isEqualTo(ApiVersionLifecycle.CREATED);
        assertThat(stored.get(0).getCreatedAt()).isEqualTo(stored.get(0).getUpdatedAt());
        assertThat(lifecycleInDb(apiId)).isEqualTo("CREATED");
    }

    @Test
    void createVersionSetsLocationHeaderToNewResource() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("v1")))
                .andExpect(status().isCreated())
                .andExpect(r -> {
                    String location = r.getResponse().getHeader(HttpHeaders.LOCATION);
                    assertThat(location).matches("/apis/" + apiId + "/versions/\\d+");
                });
    }

    @Test
    void multipleVersionsReturnedInCreationOrder() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);
        assertThat(postVersion(apiId, "v2")).isEqualTo(201);

        mockMvc.perform(get("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].version").value("v1"))
                .andExpect(jsonPath("$[1].version").value("v2"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    int indexV1 = body.indexOf("\"v1\"");
                    int indexV2 = body.indexOf("\"v2\"");
                    assertThat(indexV1).isLessThan(indexV2);
                    assertThat(body)
                            .doesNotContain("hibernate")
                            .doesNotContain("password");
                });

        assertThat(apiVersionRepository.findByApiIdOrderByIdAsc(apiId)).hasSize(2);
    }

    @Test
    void sameVersionAllowedUnderDifferentApis() throws Exception {
        Long apiA = createApi();
        Long apiB = createApi();

        assertThat(postVersion(apiA, "v1")).isEqualTo(201);
        assertThat(postVersion(apiB, "v1")).isEqualTo(201);

        assertThat(apiVersionRepository.findByApiIdOrderByIdAsc(apiA)).hasSize(1);
        assertThat(apiVersionRepository.findByApiIdOrderByIdAsc(apiB)).hasSize(1);
    }

    @Test
    void duplicateVersionUnderSameApiReturns409AndKeepsSingleRow() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("v1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("API_VERSION_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("unique constraint")
                            .doesNotContain("SQL")
                            .doesNotContain("DataIntegrityViolation")
                            .doesNotContain("at com.openbank");
                });

        assertThat(apiVersionRepository.findByApiIdOrderByIdAsc(apiId)).hasSize(1);
    }

    @Test
    void versionUniquenessEnforcedByDatabase() {
        Api api = createApiEntity();
        apiVersionRepository.saveAndFlush(new ApiVersion(api, "v1"));

        ApiVersion duplicate = new ApiVersion(api, "v1");

        assertThatThrownBy(() -> apiVersionRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void getVersionByApiAndVersionIdReturns200() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);
        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(apiId).get(0).getId();

        mockMvc.perform(get("/apis/" + apiId + "/versions/" + versionId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(versionId))
                .andExpect(jsonPath("$.apiId").value(apiId))
                .andExpect(jsonPath("$.version").value("v1"));
    }

    @Test
    void getVersionUnderNonexistentApiReturnsApiNotFound() throws Exception {
        mockMvc.perform(get("/apis/98765/versions/1")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Api with id 98765 does not exist"));
    }

    @Test
    void getNonexistentVersionReturnsVersionNotFound() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);

        mockMvc.perform(get("/apis/" + apiId + "/versions/98765")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_VERSION_NOT_FOUND"));
    }

    @Test
    void getVersionOfDifferentApiReturnsVersionNotFoundNotLeaked() throws Exception {
        Long apiWithVersion = createApi();
        Long otherApi = createApi();
        assertThat(postVersion(apiWithVersion, "v1")).isEqualTo(201);
        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(apiWithVersion).get(0).getId();

        mockMvc.perform(get("/apis/" + otherApi + "/versions/" + versionId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_VERSION_NOT_FOUND"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("\"v1\"")
                            .doesNotContain("at com.openbank");
                });
    }

    @Test
    void listVersionsUnderNonexistentApiReturnsApiNotFound() throws Exception {
        mockMvc.perform(get("/apis/98765/versions")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"));
    }

    @Test
    void postVersionUnderNonexistentApiReturnsApiNotFound() throws Exception {
        mockMvc.perform(post("/apis/98765/versions")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("v1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"));
    }

    @Test
    void invalidVersionReturned400ValidationFailed() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "not a version"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.version").value("version is required"));
    }

    @Test
    void versionWithLeadingWhitespaceReturned400WithoutBeingTrimmed() throws Exception {
        Long apiId = createApi();

        mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody("  v1 ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.version")
                        .value("version must not contain leading or trailing whitespace"));

        assertThat(apiVersionRepository.findByApiIdOrderByIdAsc(apiId)).isEmpty();
    }

    @Test
    void changeLifecycleFromCreatedToPublishedPersistsNewState() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);
        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(apiId).get(0).getId();

        mockMvc.perform(patch("/apis/" + apiId + "/versions/" + versionId + "/lifecycle")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleBody("PUBLISHED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(versionId))
                .andExpect(jsonPath("$.apiId").value(apiId))
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.lifecycle").value("PUBLISHED"));

        assertThat(lifecycleInDb(apiId)).isEqualTo("PUBLISHED");
    }

    @Test
    void lifecycleFollowsAllowedChainFromCreatedToRetired() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);
        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(apiId).get(0).getId();

        assertThat(patchLifecycle(apiId, versionId, "PUBLISHED")).isEqualTo(200);
        assertThat(patchLifecycle(apiId, versionId, "DEPRECATED")).isEqualTo(200);
        assertThat(patchLifecycle(apiId, versionId, "RETIRED")).isEqualTo(200);

        assertThat(lifecycleInDb(apiId)).isEqualTo("RETIRED");
    }

    @Test
    void changeLifecycleUpdatesUpdatedAtButKeepsCreatedAt() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);
        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(apiId).get(0).getId();

        String createdAtBefore = jdbcTemplate.queryForObject(
                "select created_at::text from api_versions where api_id = ?", String.class, apiId);
        String updatedAtBefore = jdbcTemplate.queryForObject(
                "select updated_at::text from api_versions where api_id = ?", String.class, apiId);

        assertThat(patchLifecycle(apiId, versionId, "PUBLISHED")).isEqualTo(200);

        String createdAtAfter = jdbcTemplate.queryForObject(
                "select created_at::text from api_versions where api_id = ?", String.class, apiId);
        String updatedAtAfter = jdbcTemplate.queryForObject(
                "select updated_at::text from api_versions where api_id = ?", String.class, apiId);
        assertThat(createdAtAfter).isEqualTo(createdAtBefore);
        assertThat(updatedAtAfter).isNotEqualTo(updatedAtBefore);
    }

    @Test
    void sameStateTransitionReturns409AndLeavesStateUnchanged() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);
        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(apiId).get(0).getId();

        mockMvc.perform(patch("/apis/" + apiId + "/versions/" + versionId + "/lifecycle")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleBody("CREATED")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_LIFECYCLE_TRANSITION"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("EnumMap")
                            .doesNotContain("AllowedTransitions")
                            .doesNotContain("at com.openbank");
                });

        assertThat(lifecycleInDb(apiId)).isEqualTo("CREATED");
    }

    @Test
    void backwardsTransitionReturns409WithoutChangingStateOrTimestamp() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);
        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(apiId).get(0).getId();
        assertThat(patchLifecycle(apiId, versionId, "PUBLISHED")).isEqualTo(200);
        String updatedAtBefore = jdbcTemplate.queryForObject(
                "select updated_at::text from api_versions where api_id = ?", String.class, apiId);

        mockMvc.perform(patch("/apis/" + apiId + "/versions/" + versionId + "/lifecycle")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleBody("CREATED")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_LIFECYCLE_TRANSITION"));

        assertThat(lifecycleInDb(apiId)).isEqualTo("PUBLISHED");
        String updatedAtAfter = jdbcTemplate.queryForObject(
                "select updated_at::text from api_versions where api_id = ?", String.class, apiId);
        assertThat(updatedAtAfter).isEqualTo(updatedAtBefore);
    }

    @Test
    void changeLifecycleAcrossApisReturnsVersionNotFoundWithoutLeaking() throws Exception {
        Long ownerApi = createApi();
        Long otherApi = createApi();
        assertThat(postVersion(ownerApi, "v1")).isEqualTo(201);
        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(ownerApi).get(0).getId();

        mockMvc.perform(patch("/apis/" + otherApi + "/versions/" + versionId + "/lifecycle")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleBody("PUBLISHED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_VERSION_NOT_FOUND"))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body).doesNotContain("\"v1\"");
                });
    }

    @Test
    void changeLifecycleUnderNonexistentApiReturnsApiNotFound() throws Exception {
        mockMvc.perform(patch("/apis/98765/versions/1/lifecycle")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleBody("PUBLISHED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"));
    }

    @Test
    void changeLifecycleUnknownVersionReturnsVersionNotFound() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);

        mockMvc.perform(patch("/apis/" + apiId + "/versions/98765/lifecycle")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleBody("PUBLISHED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_VERSION_NOT_FOUND"));
    }

    @Test
    void changeLifecycleInvalidLifecycleValueReturns400ValidationFailed() throws Exception {
        Long apiId = createApi();
        assertThat(postVersion(apiId, "v1")).isEqualTo(201);
        Long versionId = apiVersionRepository.findByApiIdOrderByIdAsc(apiId).get(0).getId();

        mockMvc.perform(patch("/apis/" + apiId + "/versions/" + versionId + "/lifecycle")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleBody("NOT_A_LIFECYCLE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.lifecycle").value("invalid value"));

        assertThat(lifecycleInDb(apiId)).isEqualTo("CREATED");
    }

    private Long createApi() throws Exception {
        String contextPath = "/version-api-" + SEQUENCE.incrementAndGet();
        mockMvc.perform(post("/apis")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(apiBody(contextPath)))
                .andExpect(status().isCreated());
        return apiRepository.findByContextPath(contextPath)
                .map(Api::getId)
                .orElseThrow();
    }

    private Api createApiEntity() {
        String contextPath = "/version-entity-" + SEQUENCE.incrementAndGet();
        Api api = new Api("Version API", "Api used for version tests", contextPath);
        return apiRepository.save(api);
    }

    private int postVersion(Long apiId, String version) throws Exception {
        return mockMvc.perform(post("/apis/" + apiId + "/versions")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody(version)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int patchLifecycle(Long apiId, Long versionId, String lifecycle) throws Exception {
        return mockMvc.perform(patch("/apis/" + apiId + "/versions/" + versionId + "/lifecycle")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleBody(lifecycle)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String lifecycleInDb(Long apiId) {
        return jdbcTemplate.queryForObject(
                "select lifecycle from api_versions where api_id = ?", String.class, apiId);
    }

    private String readJsonPath(org.springframework.test.web.servlet.MvcResult r, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), path);
    }

    private String versionBody(String version) {
        return """
                {
                  "version": "%s"
                }
                """.formatted(version);
    }

    private String lifecycleBody(String lifecycle) {
        return """
                {
                  "lifecycle": "%s"
                }
                """.formatted(lifecycle);
    }

    private String apiBody(String contextPath) {
        return """
                {
                  "name": "Version API",
                  "description": "Api used for version tests",
                  "contextPath": "%s"
                }
                """.formatted(contextPath);
    }

    private String adminToken() throws Exception {
        return token("1", "ADMIN", 3600);
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
