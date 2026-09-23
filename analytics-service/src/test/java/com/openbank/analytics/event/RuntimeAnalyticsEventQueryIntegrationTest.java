package com.openbank.analytics.event;

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
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class RuntimeAnalyticsEventQueryIntegrationTest {

    private static final String TEST_JWT_SECRET = "integration-test-jwt-secret-value-0123456789-ab";
    private static final Clock CLOCK = Clock.systemUTC();

    private static final Instant T0 = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-20T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-20T12:00:00Z");

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
    private RuntimeAnalyticsEventService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.execute("DELETE FROM runtime_analytics_events");
    }

    @Test
    void emptyDatabaseReturnsEmptyPage() throws Exception {
        mockMvc.perform(authed(get("/analytics/events")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void defaultPaginationReturnsFirstTwentyOfTwentyFive() throws Exception {
        for (int i = 0; i < 25; i++) {
            seed(T0.plusSeconds(i), "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L,
                    AuthenticationType.JWT, 1L, null);
        }

        mockMvc.perform(authed(get("/analytics/events")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    void customPaginationReturnsRequestedWindow() throws Exception {
        long[] savedIds = new long[7];
        for (int i = 0; i < savedIds.length; i++) {
            savedIds[i] = seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L,
                    AuthenticationType.JWT, 1L, null);
        }

        mockMvc.perform(authed(get("/analytics/events")).param("page", "1").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.items[0].id").value(savedIds[3]))
                .andExpect(jsonPath("$.items[1].id").value(savedIds[2]))
                .andExpect(jsonPath("$.items[2].id").value(savedIds[1]));
    }

    @Test
    void sizeOfOneHundredIsAllowed() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 11L, AuthenticationType.JWT, 1L, null);
        seed(T2, "/runtime/accounts", "v1", HttpMethod.GET, 200, 12L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/events")).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void sizeAboveOneHundredRejected() throws Exception {
        mockMvc.perform(authed(get("/analytics/events")).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void negativeOrNonNumericPageRejected() throws Exception {
        mockMvc.perform(authed(get("/analytics/events")).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
        mockMvc.perform(authed(get("/analytics/events")).param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void zeroSizeRejected() throws Exception {
        mockMvc.perform(authed(get("/analytics/events")).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void apiContextFilterIsExactMatch() throws Exception {
        long account = seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L,
                AuthenticationType.JWT, 1L, null);
        long payment = seed(T1, "/runtime/payments", "v1", HttpMethod.GET, 200, 11L,
                AuthenticationType.JWT, 2L, null);

        mockMvc.perform(authed(get("/analytics/events")).param("apiContext", "/runtime/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(payment));

        mockMvc.perform(authed(get("/analytics/events")).param("apiContext", "/runtime/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(account));
    }

    @Test
    void apiVersionFilterIsExactMatch() throws Exception {
        long v2 = seed(T0, "/runtime/accounts", "v2", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 11L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/events")).param("apiVersion", "v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(v2));
    }

    @Test
    void jwtAuthenticationTypeFilter() throws Exception {
        long jwt = seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 11L, AuthenticationType.CLIENT_CREDENTIAL, 42L, 5L);

        mockMvc.perform(authed(get("/analytics/events")).param("authenticationType", "JWT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(jwt));
    }

    @Test
    void clientCredentialAuthenticationTypeFilter() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        long client = seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 11L,
                AuthenticationType.CLIENT_CREDENTIAL, 42L, 5L);

        mockMvc.perform(authed(get("/analytics/events")).param("authenticationType", "CLIENT_CREDENTIAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(client));
    }

    @Test
    void invalidAuthenticationTypeRejected() throws Exception {
        mockMvc.perform(authed(get("/analytics/events")).param("authenticationType", "BASIC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void statusCodeFilter() throws Exception {
        long created = seed(T0, "/runtime/accounts", "v1", HttpMethod.POST, 201, 10L,
                AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 11L, AuthenticationType.JWT, 2L, null);

        mockMvc.perform(authed(get("/analytics/events")).param("statusCode", "201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(created));
    }

    @Test
    void outOfRangeStatusCodeRejected() throws Exception {
        mockMvc.perform(authed(get("/analytics/events")).param("statusCode", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
        mockMvc.perform(authed(get("/analytics/events")).param("statusCode", "600"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
        mockMvc.perform(authed(get("/analytics/events")).param("statusCode", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void fromFilterIsInclusive() throws Exception {
        long first = seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        long second = seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 11L, AuthenticationType.JWT, 1L, null);
        long third = seed(T2, "/runtime/accounts", "v1", HttpMethod.GET, 200, 12L, AuthenticationType.JWT, 1L, null);
        long[] ids = {first, second, third};

        mockMvc.perform(authed(get("/analytics/events")).param("from", "2026-09-20T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].id").value(ids[2]))
                .andExpect(jsonPath("$.items[1].id").value(ids[1]));
    }

    @Test
    void toFilterIsInclusive() throws Exception {
        long first = seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        long second = seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 11L, AuthenticationType.JWT, 1L, null);
        long third = seed(T2, "/runtime/accounts", "v1", HttpMethod.GET, 200, 12L, AuthenticationType.JWT, 1L, null);
        long[] ids = {first, second, third};

        mockMvc.perform(authed(get("/analytics/events")).param("to", "2026-09-20T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].id").value(ids[1]))
                .andExpect(jsonPath("$.items[1].id").value(ids[0]));
    }

    @Test
    void fromAndToConstrainWindow() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        long middle = seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 11L, AuthenticationType.JWT, 1L, null);
        seed(T2, "/runtime/accounts", "v1", HttpMethod.GET, 200, 12L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/events"))
                        .param("from", "2026-09-20T10:30:00Z")
                        .param("to", "2026-09-20T11:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(middle));
    }

    @Test
    void fromAfterToRejected() throws Exception {
        mockMvc.perform(authed(get("/analytics/events"))
                        .param("from", "2026-09-20T12:00:00Z")
                        .param("to", "2026-09-20T11:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void malformedTimestampRejected() throws Exception {
        mockMvc.perform(authed(get("/analytics/events")).param("from", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
        mockMvc.perform(authed(get("/analytics/events")).param("to", "12:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void combinedFiltersNarrowToExactSubset() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        long match = seed(T1, "/runtime/payments", "v2", HttpMethod.GET, 200, 11L,
                AuthenticationType.CLIENT_CREDENTIAL, 42L, 5L);
        seed(T1, "/runtime/payments", "v2", HttpMethod.POST, 500, 90L, AuthenticationType.CLIENT_CREDENTIAL, 7L, 8L);
        seed(T1, "/runtime/payments", "v2", HttpMethod.GET, 200, 12L, AuthenticationType.JWT, 2L, null);
        seed(T2, "/runtime/accounts", "v1", HttpMethod.GET, 200, 13L, AuthenticationType.JWT, 3L, null);

        mockMvc.perform(authed(get("/analytics/events"))
                        .param("apiContext", "/runtime/payments")
                        .param("apiVersion", "v2")
                        .param("authenticationType", "CLIENT_CREDENTIAL")
                        .param("statusCode", "200")
                        .param("from", "2026-09-20T11:00:00Z")
                        .param("to", "2026-09-20T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(match));
    }

    @Test
    void orderingIsTimestampDescThenIdDesc() throws Exception {
        long e1 = seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        long e2 = seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 11L, AuthenticationType.JWT, 1L, null);
        long e3 = seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 12L, AuthenticationType.JWT, 1L, null);
        long e4 = seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 13L, AuthenticationType.JWT, 1L, null);
        long e5 = seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 14L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/events")).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(
                        (int) e5, (int) e4, (int) e3, (int) e2, (int) e1)));
    }

    @Test
    void responseDtoNeverLeaksSensitiveFields() throws Exception {
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 42L, AuthenticationType.JWT, 7L, null);

        mockMvc.perform(authed(get("/analytics/events")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").isNumber())
                .andExpect(jsonPath("$.items[0].timestamp").value("2026-09-20T11:00:00Z"))
                .andExpect(jsonPath("$.items[0].apiContext").value("/runtime/accounts"))
                .andExpect(jsonPath("$.items[0].apiVersion").value("v1"))
                .andExpect(jsonPath("$.items[0].httpMethod").value("GET"))
                .andExpect(jsonPath("$.items[0].statusCode").value(200))
                .andExpect(jsonPath("$.items[0].latencyMs").value(42L))
                .andExpect(jsonPath("$.items[0].authenticationType").value("JWT"))
                .andExpect(jsonPath("$.items[0].userId").value(7L))
                .andExpect(jsonPath("$.items[0].applicationId").doesNotExist())
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("password")
                            .doesNotContain("secret")
                            .doesNotContain("token")
                            .doesNotContain("authorization")
                            .doesNotContain("jwt")
                            .doesNotContain("requestBody")
                            .doesNotContain("responseBody")
                            .doesNotContain("fieldErrors");
                });

        List<String> itemComponents = Arrays.stream(RuntimeAnalyticsEventResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(itemComponents).containsExactly(
                "id", "timestamp", "apiContext", "apiVersion", "httpMethod",
                "statusCode", "latencyMs", "authenticationType", "userId", "applicationId");

        List<String> pageComponents = Arrays.stream(RuntimeAnalyticsEventPageResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(pageComponents).containsExactly(
                "items", "page", "size", "totalElements", "totalPages", "last");
    }

    private long seed(Instant timestamp, String apiContext, String apiVersion, HttpMethod httpMethod,
            int statusCode, long latencyMs, AuthenticationType authenticationType, Long userId, Long applicationId) {
        return service.save(new RuntimeAnalyticsEvent(
                timestamp, apiContext, apiVersion, httpMethod, statusCode, latencyMs,
                authenticationType, userId, applicationId));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) throws Exception {
        return builder.header("Authorization", "Bearer " + adminToken());
    }

    private static String adminToken() throws Exception {
        Instant now = CLOCK.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("7")
                .claim("role", "ADMIN")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}