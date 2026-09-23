package com.openbank.analytics.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.http.HttpMethod;

import java.time.Instant;

@Entity
@Table(name = "runtime_analytics_events")
public class RuntimeAnalyticsEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "api_context", nullable = false, updatable = false)
    private String apiContext;

    @Column(name = "api_version", nullable = false, updatable = false)
    private String apiVersion;

    @Column(name = "http_method", nullable = false, updatable = false)
    private String httpMethod;

    @Column(name = "status_code", nullable = false, updatable = false)
    private int statusCode;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private long latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "authentication_type", nullable = false, updatable = false)
    private AuthenticationType authenticationType;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "application_id", updatable = false)
    private Long applicationId;

    protected RuntimeAnalyticsEventEntity() {
    }

    static RuntimeAnalyticsEventEntity from(RuntimeAnalyticsEvent event) {
        RuntimeAnalyticsEventEntity entity = new RuntimeAnalyticsEventEntity();
        entity.timestamp = event.timestamp();
        entity.apiContext = event.apiContext();
        entity.apiVersion = event.apiVersion();
        entity.httpMethod = event.httpMethod().name();
        entity.statusCode = event.statusCode();
        entity.latencyMs = event.latencyMs();
        entity.authenticationType = event.authenticationType();
        entity.userId = event.userId();
        entity.applicationId = event.applicationId();
        return entity;
    }

    public Long getId() {
        return id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getApiContext() {
        return apiContext;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public AuthenticationType getAuthenticationType() {
        return authenticationType;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getApplicationId() {
        return applicationId;
    }

    RuntimeAnalyticsEvent toEvent() {
        return new RuntimeAnalyticsEvent(
                timestamp, apiContext, apiVersion, HttpMethod.valueOf(httpMethod),
                statusCode, latencyMs, authenticationType, userId, applicationId);
    }
}