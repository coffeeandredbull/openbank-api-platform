package com.openbank.apimanagement.credential;

import java.time.Instant;

public record CredentialCreatedResponse(
        Long id,
        Long applicationId,
        String clientId,
        String clientSecret,
        CredentialStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public CredentialCreatedResponse(
            Long id,
            Long applicationId,
            String clientId,
            String clientSecret,
            CredentialStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this(id, applicationId, clientId, clientSecret, status, null, createdAt, updatedAt);
    }

    public static CredentialCreatedResponse from(Credential credential, String plaintextClientSecret) {
        return new CredentialCreatedResponse(
                credential.getId(),
                credential.getApplication().getId(),
                credential.getClientId(),
                plaintextClientSecret,
                credential.getStatus(),
                credential.getExpiresAt(),
                credential.getCreatedAt(),
                credential.getUpdatedAt()
        );
    }
}