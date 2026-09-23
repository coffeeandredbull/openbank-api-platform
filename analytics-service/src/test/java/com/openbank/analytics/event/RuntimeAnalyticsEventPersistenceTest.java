package com.openbank.analytics.event;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class RuntimeAnalyticsEventPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private RuntimeAnalyticsEventService service;

    @Autowired
    private RuntimeAnalyticsEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.execute("DELETE FROM runtime_analytics_events");
    }

    private static final Instant TIMESTAMP = Instant.parse("2026-09-22T08:00:00Z");

    private static RuntimeAnalyticsEvent jwtEvent() {
        return new RuntimeAnalyticsEvent(TIMESTAMP, "/runtime/accounts", "v1", HttpMethod.GET, 200, 42L,
                AuthenticationType.JWT, 7L, null);
    }

    private static RuntimeAnalyticsEvent clientCredentialEvent() {
        return new RuntimeAnalyticsEvent(TIMESTAMP, "/runtime/payments", "v1", HttpMethod.POST, 201, 128L,
                AuthenticationType.CLIENT_CREDENTIAL, 42L, 5L);
    }

    @Test
    void jwtEventPersists() {
        long id = service.save(jwtEvent());

        RuntimeAnalyticsEventEntity stored = repository.findById(id).orElseThrow();
        assertThat(stored.getId()).isEqualTo(id);
        assertThat(stored.getTimestamp()).isEqualTo(TIMESTAMP);
        assertThat(stored.getApiContext()).isEqualTo("/runtime/accounts");
        assertThat(stored.getApiVersion()).isEqualTo("v1");
        assertThat(stored.getHttpMethod()).isEqualTo("GET");
        assertThat(stored.getStatusCode()).isEqualTo(200);
        assertThat(stored.getLatencyMs()).isEqualTo(42L);
        assertThat(stored.getAuthenticationType()).isEqualTo(AuthenticationType.JWT);
        assertThat(stored.getUserId()).isEqualTo(7L);
        assertThat(stored.getApplicationId()).isNull();
    }

    @Test
    void clientCredentialEventPersists() {
        long id = service.save(clientCredentialEvent());

        RuntimeAnalyticsEventEntity stored = repository.findById(id).orElseThrow();
        assertThat(stored.getId()).isEqualTo(id);
        assertThat(stored.getTimestamp()).isEqualTo(TIMESTAMP);
        assertThat(stored.getApiContext()).isEqualTo("/runtime/payments");
        assertThat(stored.getApiVersion()).isEqualTo("v1");
        assertThat(stored.getHttpMethod()).isEqualTo("POST");
        assertThat(stored.getStatusCode()).isEqualTo(201);
        assertThat(stored.getLatencyMs()).isEqualTo(128L);
        assertThat(stored.getAuthenticationType()).isEqualTo(AuthenticationType.CLIENT_CREDENTIAL);
        assertThat(stored.getUserId()).isEqualTo(42L);
        assertThat(stored.getApplicationId()).isEqualTo(5L);
    }

    @Test
    void generatedIdIsPositiveAndUniqueAcrossSaves() {
        long firstId = service.save(jwtEvent());
        long secondId = service.save(jwtEvent());

        assertThat(firstId).isPositive();
        assertThat(secondId).isPositive();
        assertThat(secondId).isNotEqualTo(firstId);
    }

    @Test
    void timestampIsPersisted() {
        long id = service.save(jwtEvent());

        Instant stored = repository.findById(id).orElseThrow().getTimestamp();
        assertThat(stored).isEqualTo(TIMESTAMP);
    }

    @Test
    void apiContextIsPersisted() {
        long id = service.save(jwtEvent());

        String stored = repository.findById(id).orElseThrow().getApiContext();
        assertThat(stored).isEqualTo("/runtime/accounts");
    }

    @Test
    void apiVersionIsPersisted() {
        long id = service.save(jwtEvent());

        String stored = repository.findById(id).orElseThrow().getApiVersion();
        assertThat(stored).isEqualTo("v1");
    }

    @Test
    void httpMethodIsPersisted() {
        long id = service.save(jwtEvent());

        String stored = repository.findById(id).orElseThrow().getHttpMethod();
        assertThat(stored).isEqualTo("GET");
    }

    @Test
    void statusCodeIsPersisted() {
        long id = service.save(clientCredentialEvent());

        int stored = repository.findById(id).orElseThrow().getStatusCode();
        assertThat(stored).isEqualTo(201);
    }

    @Test
    void latencyMsIsPersisted() {
        long id = service.save(clientCredentialEvent());

        long stored = repository.findById(id).orElseThrow().getLatencyMs();
        assertThat(stored).isEqualTo(128L);
    }

    @Test
    void authenticationTypeIsPersistedAsString() {
        long jwtId = service.save(jwtEvent());
        long credentialId = service.save(clientCredentialEvent());

        String storedJwt = jdbcTemplate.queryForObject(
                "select authentication_type from runtime_analytics_events where id = ?", String.class, jwtId);
        String storedCredential = jdbcTemplate.queryForObject(
                "select authentication_type from runtime_analytics_events where id = ?", String.class, credentialId);
        assertThat(storedJwt).isEqualTo("JWT");
        assertThat(storedCredential).isEqualTo("CLIENT_CREDENTIAL");
    }

    @Test
    void jwtEventPersistsWithNullApplicationId() {
        long id = service.save(jwtEvent());

        RuntimeAnalyticsEventEntity stored = repository.findById(id).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(7L);
        assertThat(stored.getApplicationId()).isNull();
    }

    @Test
    void clientCredentialEventPersistsWithUserIdAndApplicationId() {
        long id = service.save(clientCredentialEvent());

        RuntimeAnalyticsEventEntity stored = repository.findById(id).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(42L);
        assertThat(stored.getApplicationId()).isEqualTo(5L);
    }

    @Test
    void savedEventIsRetrievedByIdThroughService() {
        long id = service.save(clientCredentialEvent());

        Optional<RuntimeAnalyticsEvent> retrieved = service.findById(id);

        assertThat(retrieved).isPresent();
        RuntimeAnalyticsEvent event = retrieved.orElseThrow();
        assertThat(event.timestamp()).isEqualTo(TIMESTAMP);
        assertThat(event.apiContext()).isEqualTo("/runtime/payments");
        assertThat(event.apiVersion()).isEqualTo("v1");
        assertThat(event.httpMethod()).isEqualTo(HttpMethod.POST);
        assertThat(event.statusCode()).isEqualTo(201);
        assertThat(event.latencyMs()).isEqualTo(128L);
        assertThat(event.authenticationType()).isEqualTo(AuthenticationType.CLIENT_CREDENTIAL);
        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.applicationId()).isEqualTo(5L);
    }

    @Test
    void multipleSavedEventsRemainSeparate() {
        long firstId = service.save(jwtEvent());
        long secondId = service.save(clientCredentialEvent());

        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findById(firstId).orElseThrow().getApiContext()).isEqualTo("/runtime/accounts");
        assertThat(repository.findById(firstId).orElseThrow().getAuthenticationType())
                .isEqualTo(AuthenticationType.JWT);
        assertThat(repository.findById(secondId).orElseThrow().getApiContext()).isEqualTo("/runtime/payments");
        assertThat(repository.findById(secondId).orElseThrow().getAuthenticationType())
                .isEqualTo(AuthenticationType.CLIENT_CREDENTIAL);
    }

    @Test
    void serviceRejectsInvalidEventWithoutPersisting() {
        RuntimeAnalyticsEvent invalid = new RuntimeAnalyticsEvent(TIMESTAMP, "/runtime/accounts", "v1", HttpMethod.GET,
                99, 42L, AuthenticationType.JWT, 7L, null);

        assertThatThrownBy(() -> service.save(invalid))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(repository.count()).isZero();
    }

    @Test
    void tableHasExpectedSchemaWithoutSensitiveColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'runtime_analytics_events' "
                        + "order by ordinal_position",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "event_timestamp", "api_context", "api_version", "http_method",
                "status_code", "latency_ms", "authentication_type", "user_id", "application_id");

        List<String> nullableColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'runtime_analytics_events' "
                        + "and is_nullable = 'YES'",
                String.class);
        assertThat(nullableColumns).containsExactlyInAnyOrder("user_id", "application_id");

        assertThat(columns).noneMatch(c -> containsAnyIgnoringCase(c,
                "password", "secret", "hash", "token", "authorization", "body"));

        String dataType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_schema = current_schema() and table_name = 'runtime_analytics_events' "
                        + "and column_name = 'authentication_type'",
                String.class);
        assertThat(dataType).isIn("character varying", "varchar", "text");
    }

    private boolean containsAnyIgnoringCase(String value, String... needles) {
        for (String needle : needles) {
            if (value.toLowerCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }
}