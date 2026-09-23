package com.openbank.analytics.usage;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.analytics.event.AuthenticationType;
import com.openbank.analytics.event.RuntimeAnalyticsEvent;
import com.openbank.analytics.event.RuntimeAnalyticsEventService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-value-0123456789-ab")
class RuntimeAnalyticsUsageIntegrationTest {

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
    void noParametersAggregatesAllRows() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 100L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 201, 150L, AuthenticationType.JWT, 1L, null);
        seed(T2, "/runtime/payments", "v2", HttpMethod.POST, 500, 900L, AuthenticationType.CLIENT_CREDENTIAL, 42L, 5L);
        seed(T1, "/runtime/payments", "v1", HttpMethod.GET, 404, 30L, AuthenticationType.JWT, 2L, null);
        seed(T2, "/runtime/accounts", "v2", HttpMethod.GET, 200, 200L, AuthenticationType.JWT, 3L, null);

        mockMvc.perform(authed(get("/analytics/usage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(5))
                .andExpect(jsonPath("$.successfulRequests").value(3))
                .andExpect(jsonPath("$.clientErrorRequests").value(1))
                .andExpect(jsonPath("$.serverErrorRequests").value(1));
    }

    @Test
    void statusClassesAreCountedInPostgres() throws Exception {
        for (int status : new int[]{200, 201, 299, 300, 400, 499, 500, 599}) {
            seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, status, 10L, AuthenticationType.JWT, 1L, null);
        }

        mockMvc.perform(authed(get("/analytics/usage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(8))
                .andExpect(jsonPath("$.successfulRequests").value(3))
                .andExpect(jsonPath("$.clientErrorRequests").value(2))
                .andExpect(jsonPath("$.serverErrorRequests").value(2));
    }

    @Test
    void averageLatencyMsIsComputedByPostgres() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 100L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 150L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/usage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageLatencyMs").value(125.0));
    }

    @Test
    void emptyWindowReturnsZeroCountsAndZeroAverage() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 100L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/usage"))
                        .param("from", "2026-09-21T00:00:00Z")
                        .param("to", "2026-09-22T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(0))
                .andExpect(jsonPath("$.successfulRequests").value(0))
                .andExpect(jsonPath("$.clientErrorRequests").value(0))
                .andExpect(jsonPath("$.serverErrorRequests").value(0))
                .andExpect(jsonPath("$.averageLatencyMs").value(0.0));

        mockMvc.perform(authed(get("/analytics/usage")).param("apiContext", "/runtime/does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(0))
                .andExpect(jsonPath("$.averageLatencyMs").value(0.0));
    }

    @Test
    void fromFilterIsInclusive() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T2, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/usage")).param("from", "2026-09-20T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(2));
    }

    @Test
    void toFilterIsInclusive() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T2, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/usage")).param("to", "2026-09-20T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(2));
    }

    @Test
    void fromAndToNarrowToExactWindow() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T2, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/usage"))
                        .param("from", "2026-09-20T10:30:00Z")
                        .param("to", "2026-09-20T11:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1));
    }

    @Test
    void fromAfterToReturns400() throws Exception {
        mockMvc.perform(authed(get("/analytics/usage"))
                        .param("from", "2026-09-20T12:00:00Z")
                        .param("to", "2026-09-20T11:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void malformedTimestampReturns400() throws Exception {
        mockMvc.perform(authed(get("/analytics/usage")).param("from", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
        mockMvc.perform(authed(get("/analytics/usage")).param("to", "12:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void apiContextFilterIsExactMatch() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/payments", "v1", HttpMethod.GET, 200, 11L, AuthenticationType.JWT, 1L, null);
        seed(T2, "/runtime/payments/v1", "v1", HttpMethod.GET, 200, 12L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/usage")).param("apiContext", "/runtime/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1));
    }

    @Test
    void apiVersionFilterIsExactMatch() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/accounts", "v2", HttpMethod.GET, 200, 11L, AuthenticationType.JWT, 1L, null);
        seed(T2, "/runtime/accounts", "v20", HttpMethod.GET, 200, 12L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/usage")).param("apiVersion", "v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1));
    }

    @Test
    void combinedFiltersNarrowToExactSubset() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);
        seed(T1, "/runtime/payments", "v2", HttpMethod.GET, 200, 11L, AuthenticationType.CLIENT_CREDENTIAL, 42L, 5L);
        seed(T1, "/runtime/payments", "v2", HttpMethod.POST, 500, 90L, AuthenticationType.CLIENT_CREDENTIAL, 7L, 8L);
        seed(T1, "/runtime/accounts", "v2", HttpMethod.GET, 200, 12L, AuthenticationType.JWT, 2L, null);

        mockMvc.perform(authed(get("/analytics/usage"))
                        .param("apiContext", "/runtime/payments")
                        .param("apiVersion", "v2")
                        .param("from", "2026-09-20T11:00:00Z")
                        .param("to", "2026-09-20T11:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(2))
                .andExpect(jsonPath("$.successfulRequests").value(1))
                .andExpect(jsonPath("$.serverErrorRequests").value(1))
                .andExpect(jsonPath("$.clientErrorRequests").value(0));
    }

    @Test
    void blankApiContextIsTreatedAsAbsent() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/usage")).param("apiContext", " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1));
    }

    @Test
    void developerJwtIsAuthorized() throws Exception {
        mockMvc.perform(get("/analytics/usage")
                        .header("Authorization", "Bearer " + token("9", "DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(0));
    }

    @Test
    void noJwtReturns401() throws Exception {
        mockMvc.perform(get("/analytics/usage"))
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
    void jwtWithUnknownRoleReturns403() throws Exception {
        mockMvc.perform(get("/analytics/usage")
                        .header("Authorization", "Bearer " + token("7", "CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void tamperedJwtReturns401() throws Exception {
        String token = token("7", "ADMIN");
        String[] parts = token.split("\\.");
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? "B" : "A") + parts[2].substring(1);

        mockMvc.perform(get("/analytics/usage")
                        .header("Authorization", "Bearer " + String.join(".", parts)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void eventsEndpointRemainsUnaffected() throws Exception {
        seed(T0, "/runtime/accounts", "v1", HttpMethod.GET, 200, 10L, AuthenticationType.JWT, 1L, null);

        mockMvc.perform(authed(get("/analytics/events")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void responseOnlyExposesAggregatedMetrics() throws Exception {
        seed(T1, "/runtime/accounts", "v1", HttpMethod.GET, 200, 42L, AuthenticationType.CLIENT_CREDENTIAL, 7L, 5L);

        mockMvc.perform(authed(get("/analytics/usage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1))
                .andExpect(jsonPath("$.successfulRequests").value(1))
                .andExpect(jsonPath("$.clientErrorRequests").value(0))
                .andExpect(jsonPath("$.serverErrorRequests").value(0))
                .andExpect(jsonPath("$.averageLatencyMs").value(42.0))
                .andExpect(r -> {
                    String body = r.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("password")
                            .doesNotContain("secret")
                            .doesNotContain("token")
                            .doesNotContain("authorization")
                            .doesNotContain("jwt")
                            .doesNotContain("apiContext")
                            .doesNotContain("apiVersion")
                            .doesNotContain("userId")
                            .doesNotContain("applicationId")
                            .doesNotContain("statusCode")
                            .doesNotContain("httpMethod")
                            .doesNotContain("authenticationType");
                });

        java.util.List<String> components = Arrays.stream(RuntimeAnalyticsUsageSummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(components).containsExactly(
                "totalRequests", "successfulRequests", "clientErrorRequests",
                "serverErrorRequests", "averageLatencyMs");
    }

    private long seed(Instant timestamp, String apiContext, String apiVersion, HttpMethod httpMethod,
            int statusCode, long latencyMs, AuthenticationType authenticationType, Long userId, Long applicationId) {
        return service.save(new RuntimeAnalyticsEvent(
                timestamp, apiContext, apiVersion, httpMethod, statusCode, latencyMs,
                authenticationType, userId, applicationId));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) throws Exception {
        return builder.header("Authorization", "Bearer " + token("7", "ADMIN"));
    }

    private static String token(String subject, String role) throws Exception {
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
        jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}