package com.openbank.analytics.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeAnalyticsEventServiceTest {

    @Mock
    private RuntimeAnalyticsEventRepository repository;

    @InjectMocks
    private RuntimeAnalyticsEventService service;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-22T08:00:00Z");

    @Test
    void saveMapsDomainEventToEntityAndReturnsGeneratedId() {
        RuntimeAnalyticsEvent event = new RuntimeAnalyticsEvent(
                TIMESTAMP, "/runtime/accounts", "v1", HttpMethod.GET, 200, 42L,
                AuthenticationType.JWT, 7L, null);
        when(repository.save(any(RuntimeAnalyticsEventEntity.class))).thenAnswer(invocation -> {
            RuntimeAnalyticsEventEntity entity = invocation.getArgument(0);
            setId(entity, 100L);
            return entity;
        });

        long id = service.save(event);

        ArgumentCaptor<RuntimeAnalyticsEventEntity> captor = ArgumentCaptor.forClass(RuntimeAnalyticsEventEntity.class);
        verify(repository).save(captor.capture());
        RuntimeAnalyticsEventEntity entity = captor.getValue();
        assertThat(entity.getTimestamp()).isEqualTo(TIMESTAMP);
        assertThat(entity.getApiContext()).isEqualTo("/runtime/accounts");
        assertThat(entity.getApiVersion()).isEqualTo("v1");
        assertThat(entity.getHttpMethod()).isEqualTo("GET");
        assertThat(entity.getStatusCode()).isEqualTo(200);
        assertThat(entity.getLatencyMs()).isEqualTo(42L);
        assertThat(entity.getAuthenticationType()).isEqualTo(AuthenticationType.JWT);
        assertThat(entity.getUserId()).isEqualTo(7L);
        assertThat(entity.getApplicationId()).isNull();

        assertThat(id).isEqualTo(100L);
    }

    @Test
    void findByIdMapsEntityBackToDomainEvent() {
        RuntimeAnalyticsEventEntity stored = new RuntimeAnalyticsEventEntity();
        setId(stored, 100L);
        stubFields(stored, TIMESTAMP, "/runtime/accounts", "v1", "GET", 200, 42L,
                AuthenticationType.JWT, 7L, null);
        when(repository.findById(100L)).thenReturn(Optional.of(stored));

        Optional<RuntimeAnalyticsEvent> result = service.findById(100L);

        assertThat(result).isPresent();
        RuntimeAnalyticsEvent event = result.orElseThrow();
        assertThat(event.timestamp()).isEqualTo(TIMESTAMP);
        assertThat(event.apiContext()).isEqualTo("/runtime/accounts");
        assertThat(event.apiVersion()).isEqualTo("v1");
        assertThat(event.httpMethod()).isEqualTo(HttpMethod.GET);
        assertThat(event.statusCode()).isEqualTo(200);
        assertThat(event.latencyMs()).isEqualTo(42L);
        assertThat(event.authenticationType()).isEqualTo(AuthenticationType.JWT);
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.applicationId()).isNull();
    }

    private void setId(RuntimeAnalyticsEventEntity entity, long id) {
        setField(entity, "id", id);
    }

    private void stubFields(RuntimeAnalyticsEventEntity entity, Instant timestamp, String apiContext,
            String apiVersion, String httpMethod, int statusCode, long latencyMs,
            AuthenticationType authenticationType, Long userId, Long applicationId) {
        setField(entity, "timestamp", timestamp);
        setField(entity, "apiContext", apiContext);
        setField(entity, "apiVersion", apiVersion);
        setField(entity, "httpMethod", httpMethod);
        setField(entity, "statusCode", statusCode);
        setField(entity, "latencyMs", latencyMs);
        setField(entity, "authenticationType", authenticationType);
        setField(entity, "userId", userId);
        setField(entity, "applicationId", applicationId);
    }

    private void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}