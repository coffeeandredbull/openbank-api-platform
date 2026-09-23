package com.openbank.gateway.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class RuntimeAnalyticsEventTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-09-23T12:34:56.789Z");

    private RuntimeAnalyticsEvent baseEvent() {
        return new RuntimeAnalyticsEvent(
                TIMESTAMP,
                "/payments",
                "v1",
                HttpMethod.GET,
                200,
                42,
                AuthenticationType.JWT,
                1L,
                null);
    }

    @Test
    void jwtEventCanBeCreated() {
        RuntimeAnalyticsEvent event = baseEvent();

        assertThat(event.authenticationType()).isEqualTo(AuthenticationType.JWT);
        assertThat(event.userId()).isEqualTo(1L);
    }

    @Test
    void clientCredentialEventCanBeCreated() {
        RuntimeAnalyticsEvent event = new RuntimeAnalyticsEvent(
                TIMESTAMP, "/payments", "v1", HttpMethod.GET, 201, 15,
                AuthenticationType.CLIENT_CREDENTIAL, 42L, 7L);

        assertThat(event.authenticationType()).isEqualTo(AuthenticationType.CLIENT_CREDENTIAL);
    }

    @Test
    void jwtEventAllowsNullApplicationId() {
        assertThat(baseEvent().applicationId()).isNull();
    }

    @Test
    void clientCredentialEventCarriesBothUserIdAndApplicationId() {
        RuntimeAnalyticsEvent event = new RuntimeAnalyticsEvent(
                TIMESTAMP, "/payments", "v1", HttpMethod.POST, 201, 15,
                AuthenticationType.CLIENT_CREDENTIAL, 42L, 7L);

        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.applicationId()).isEqualTo(7L);
    }

    @Test
    void timestampIsRepresentedExactly() {
        assertThat(baseEvent().timestamp()).isEqualTo(TIMESTAMP);
    }

    @Test
    void requestContextIsRetainedCorrectly() {
        RuntimeAnalyticsEvent event = new RuntimeAnalyticsEvent(
                TIMESTAMP, "/accounts", "v2", HttpMethod.DELETE, 204, 1234,
                AuthenticationType.JWT, 9L, null);

        assertThat(event.apiContext()).isEqualTo("/accounts");
        assertThat(event.apiVersion()).isEqualTo("v2");
        assertThat(event.httpMethod()).isEqualTo(HttpMethod.DELETE);
        assertThat(event.statusCode()).isEqualTo(204);
        assertThat(event.latencyMs()).isEqualTo(1234L);
    }

    @Test
    void nullTimestampIsRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new RuntimeAnalyticsEvent(null, "/payments", "v1", HttpMethod.GET, 200, 1,
                        AuthenticationType.JWT, 1L, null));
    }

    @Test
    void nullHttpMethodIsRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new RuntimeAnalyticsEvent(TIMESTAMP, "/payments", "v1", null, 200, 1,
                        AuthenticationType.JWT, 1L, null));
    }

    @Test
    void nullAuthenticationTypeIsRejected() {
        assertThatNullPointerException().isThrownBy(() ->
                new RuntimeAnalyticsEvent(TIMESTAMP, "/payments", "v1", HttpMethod.GET, 200, 1,
                        null, 1L, null));
    }

    @Test
    void blankApiContextIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RuntimeAnalyticsEvent(TIMESTAMP, "   ", "v1", HttpMethod.GET, 200, 1,
                        AuthenticationType.JWT, 1L, null));
    }

    @Test
    void blankApiVersionIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RuntimeAnalyticsEvent(TIMESTAMP, "/payments", "", HttpMethod.GET, 200, 1,
                        AuthenticationType.JWT, 1L, null));
    }

    @Test
    void outOfRangeStatusCodeIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RuntimeAnalyticsEvent(TIMESTAMP, "/payments", "v1", HttpMethod.GET, 99, 1,
                        AuthenticationType.JWT, 1L, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RuntimeAnalyticsEvent(TIMESTAMP, "/payments", "v1", HttpMethod.GET, 600, 1,
                        AuthenticationType.JWT, 1L, null));
    }

    @Test
    void negativeLatencyIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RuntimeAnalyticsEvent(TIMESTAMP, "/payments", "v1", HttpMethod.GET, 200, -1,
                        AuthenticationType.JWT, 1L, null));
    }

    @Test
    void authenticationTypeContainsExactlyTheSupportedFlows() {
        assertThat(AuthenticationType.values())
                .containsExactly(AuthenticationType.JWT, AuthenticationType.CLIENT_CREDENTIAL);
    }
}