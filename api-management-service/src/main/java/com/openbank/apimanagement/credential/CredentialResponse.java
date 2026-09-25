package com.openbank.apimanagement.credential;

import java.time.Instant;

public record CredentialResponse(
        Long id,
        Long applicationId,
        String clientId,
        CredentialStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public CredentialResponse(
            Long id,
            Long applicationId,
            String clientId,
            CredentialStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this(id, applicationId, clientId, status, null, createdAt, updatedAt);
    }

    public static CredentialResponse from(Credential credential) {
        return new CredentialResponse(
                credential.getId(),
                credential.getApplication().getId(),
                credential.getClientId(),
                credential.getStatus(),
                credential.getExpiresAt(),
                credential.getCreatedAt(),
                credential.getUpdatedAt()
        );
    }
}