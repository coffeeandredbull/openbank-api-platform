package com.openbank.gateway.analytics;

/**
 * The runtime authentication flows that a managed API invocation can use. A
 * recorded {@link RuntimeAnalyticsEvent} carries exactly one of these values;
 * the type determines the meaning of the event's identity fields
 * ({@code userId} / {@code applicationId}).
 */
public enum AuthenticationType {

    /** A user authenticated with {@code Authorization: Bearer <jwt>}. */
    JWT,

    /** An application authenticated with application client credentials. */
    CLIENT_CREDENTIAL
}