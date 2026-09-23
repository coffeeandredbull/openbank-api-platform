package com.openbank.analytics.internal;

import com.openbank.analytics.event.AuthenticationType;
import com.openbank.analytics.event.RuntimeAnalyticsEventEntity;
import com.openbank.analytics.event.RuntimeAnalyticsEventRepository;
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

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.internal.analytics-token=" + RuntimeAnalyticsEventIngestionIntegrationTest.TOKEN)
class RuntimeAnalyticsEventIngestionIntegrationTest {

    static final String TOKEN = "test-analytics-internal-token";

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
    private RuntimeAnalyticsEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.execute("DELETE FROM runtime_analytics_events");
    }

    private static final Instant TIMESTAMP = Instant.parse("2026-09-22T08:00:00Z");

    private static String validJwtJson() {
        return """
                {
                  "timestamp": "2026-09-22T08:00:00Z",
                  "apiContext": "/payments",
                  "apiVersion": "v1",
                  "httpMethod": "GET",
                  "statusCode": 201,
                  "latencyMs": 42,
                  "authenticationType": "JWT",
                  "userId": 1,
                  "applicationId": null
                }
                """;
    }

    private static String clientCredentialJson() {
        return """
                {
                  "timestamp": "2026-09-22T08:00:00Z",
                  "apiContext": "/payments",
                  "apiVersion": "v1",
                  "httpMethod": "POST",
                  "statusCode": 200,
                  "latencyMs": 128,
                  "authenticationType": "CLIENT_CREDENTIAL",
                  "userId": 42,
                  "applicationId": 7
                }
                """;
    }

    private org.springframework.test.web.servlet.ResultActions postWithToken(String body) throws Exception {
        return mockMvc.perform(post("/internal/analytics/events")
                .header(InternalApiAuthProperties.INTERNAL_TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void validRequestAcceptedWith202() throws Exception {
        postWithToken(validJwtJson()).andExpect(status().isAccepted());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void requestWithoutTokenRejectedWith401() throws Exception {
        mockMvc.perform(post("/internal/analytics/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJwtJson()))
                .andExpect(status().isUnauthorized());
        assertThat(repository.count()).isZero();
    }

    @Test
    void requestWithWrongTokenRejectedWith401() throws Exception {
        mockMvc.perform(post("/internal/analytics/events")
                        .header(InternalApiAuthProperties.INTERNAL_TOKEN_HEADER, "a-completely-wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJwtJson()))
                .andExpect(status().isUnauthorized());
        assertThat(repository.count()).isZero();
    }

    @Test
    void outOfRangeStatusCodeRejectedWith400() throws Exception {
        String body = validJwtJson().replace("\"statusCode\": 201", "\"statusCode\": 99");

        postWithToken(body).andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void blankApiContextRejectedWith400() throws Exception {
        String body = validJwtJson().replace("\"apiContext\": \"/payments\"", "\"apiContext\": \"  \"");

        postWithToken(body).andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void negativeLatencyRejectedWith400() throws Exception {
        String body = validJwtJson().replace("\"latencyMs\": 42", "\"latencyMs\": -1");

        postWithToken(body).andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void unknownHttpMethodRejectedWith400() throws Exception {
        String body = validJwtJson().replace("\"httpMethod\": \"GET\"", "\"httpMethod\": \"FLY\"");

        postWithToken(body).andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void malformedJsonRejectedWith400() throws Exception {
        postWithToken("{not json at all").andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void acceptedRequestIsPersistedWithAllFields() throws Exception {
        postWithToken(validJwtJson()).andExpect(status().isAccepted());

        RuntimeAnalyticsEventEntity stored = repository.findAll().get(0);
        assertThat(stored.getTimestamp()).isEqualTo(TIMESTAMP);
        assertThat(stored.getApiContext()).isEqualTo("/payments");
        assertThat(stored.getApiVersion()).isEqualTo("v1");
        assertThat(stored.getHttpMethod()).isEqualTo("GET");
        assertThat(stored.getStatusCode()).isEqualTo(201);
        assertThat(stored.getLatencyMs()).isEqualTo(42L);
        assertThat(stored.getAuthenticationType()).isEqualTo(AuthenticationType.JWT);
        assertThat(stored.getUserId()).isEqualTo(1L);
        assertThat(stored.getApplicationId()).isNull();
    }

    @Test
    void clientCredentialRequestPersistsBothIdentityIds() throws Exception {
        postWithToken(clientCredentialJson()).andExpect(status().isAccepted());

        RuntimeAnalyticsEventEntity stored = repository.findAll().get(0);
        assertThat(stored.getAuthenticationType()).isEqualTo(AuthenticationType.CLIENT_CREDENTIAL);
        assertThat(stored.getUserId()).isEqualTo(42L);
        assertThat(stored.getApplicationId()).isEqualTo(7L);
    }

    @Test
    void sensitiveJsonFieldsAreIgnoredAndNeverPersisted() throws Exception {
        String body = """
                {
                  "timestamp": "2026-09-22T08:00:00Z",
                  "apiContext": "/payments",
                  "apiVersion": "v1",
                  "httpMethod": "GET",
                  "statusCode": 201,
                  "latencyMs": 42,
                  "authenticationType": "JWT",
                  "userId": 1,
                  "applicationId": null,
                  "jwt": "eyJ....",
                  "authorization": "Bearer xyz",
                  "clientSecret": "s3cr3t",
                  "requestBody": "{\\"secret\\":true}",
                  "password": "hunter2"
                }
                """;

        postWithToken(body).andExpect(status().isAccepted());

        RuntimeAnalyticsEventEntity stored = repository.findAll().get(0);
        assertThat(stored.getUserId()).isEqualTo(1L);

        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'runtime_analytics_events'",
                String.class);
        assertThat(columns).doesNotContain("authorization", "jwt", "client_secret", "request_body", "password");
    }

    @Test
    void dtoExposesOnlyTheNineAllowedAnalyticsFields() {
        List<String> components = Arrays.stream(RuntimeAnalyticsEventRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(components).containsExactly(
                "timestamp", "apiContext", "apiVersion", "httpMethod",
                "statusCode", "latencyMs", "authenticationType", "userId", "applicationId");
    }
}