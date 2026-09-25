package com.openbank.apimanagement.credential;

import java.time.Instant;

public record CredentialRotatedResponse(
        Long id,
        Long applicationId,
        String clientId,
        String clientSecret,
        CredentialStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public CredentialRotatedResponse(
            Long id,
            Long applicationId,
            String clientId,
            String clientSecret,
            CredentialStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this(id, applicationId, clientId, clientSecret, status, null, createdAt, updatedAt);
    }

    public static CredentialRotatedResponse from(Credential credential, String plaintextClientSecret) {
        return new CredentialRotatedResponse(
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