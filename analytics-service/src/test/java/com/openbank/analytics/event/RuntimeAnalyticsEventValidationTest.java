package com.openbank.analytics.event;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeAnalyticsEventValidationTest {

    private static final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static RuntimeAnalyticsEvent baseEvent() {
        return new RuntimeAnalyticsEvent(
                Instant.parse("2026-09-22T08:00:00Z"),
                "/runtime/accounts",
                "v1",
                HttpMethod.GET,
                200,
                42L,
                AuthenticationType.JWT,
                7L,
                null);
    }

    private static Set<ConstraintViolation<RuntimeAnalyticsEvent>> violations(RuntimeAnalyticsEvent event) {
        return validator.validate(event);
    }

    private void assertViolationOn(RuntimeAnalyticsEvent event, String propertyPath) {
        Set<ConstraintViolation<RuntimeAnalyticsEvent>> violations = violations(event);
        assertThat(violations)
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo(propertyPath);
                });
    }

    @Test
    void validEventPassesValidation() {
        assertThat(violations(baseEvent())).isEmpty();
    }

    @Test
    void rejectsMissingTimestamp() {
        assertViolationOn(
                new RuntimeAnalyticsEvent(null, "/runtime/accounts", "v1", HttpMethod.GET, 200, 42L,
                        AuthenticationType.JWT, 7L, null),
                "timestamp");
    }

    @Test
    void rejectsBlankApiContext() {
        assertViolationOn(
                new RuntimeAnalyticsEvent(Instant.parse("2026-09-22T08:00:00Z"), " ", "v1", HttpMethod.GET, 200, 42L,
                        AuthenticationType.JWT, 7L, null),
                "apiContext");
    }

    @Test
    void rejectsBlankApiVersion() {
        assertViolationOn(
                new RuntimeAnalyticsEvent(Instant.parse("2026-09-22T08:00:00Z"), "/runtime/accounts", "", HttpMethod.GET,
                        200, 42L, AuthenticationType.JWT, 7L, null),
                "apiVersion");
    }

    @Test
    void rejectsMissingHttpMethod() {
        assertViolationOn(
                new RuntimeAnalyticsEvent(Instant.parse("2026-09-22T08:00:00Z"), "/runtime/accounts", "v1", null, 200,
                        42L, AuthenticationType.JWT, 7L, null),
                "httpMethod");
    }

    @Test
    void rejectsStatusCodeBelow100() {
        assertViolationOn(
                new RuntimeAnalyticsEvent(Instant.parse("2026-09-22T08:00:00Z"), "/runtime/accounts", "v1",
                        HttpMethod.GET, 99, 42L, AuthenticationType.JWT, 7L, null),
                "statusCode");
    }

    @Test
    void rejectsStatusCodeAbove599() {
        assertViolationOn(
                new RuntimeAnalyticsEvent(Instant.parse("2026-09-22T08:00:00Z"), "/runtime/accounts", "v1",
                        HttpMethod.GET, 600, 42L, AuthenticationType.JWT, 7L, null),
                "statusCode");
    }

    @Test
    void rejectsNegativeLatencyMs() {
        assertViolationOn(
                new RuntimeAnalyticsEvent(Instant.parse("2026-09-22T08:00:00Z"), "/runtime/accounts", "v1",
                        HttpMethod.GET, 200, -1L, AuthenticationType.JWT, 7L, null),
                "latencyMs");
    }

    @Test
    void rejectsMissingAuthenticationType() {
        assertViolationOn(
                new RuntimeAnalyticsEvent(Instant.parse("2026-09-22T08:00:00Z"), "/runtime/accounts", "v1",
                        HttpMethod.GET, 200, 42L, null, 7L, null),
                "authenticationType");
    }
}