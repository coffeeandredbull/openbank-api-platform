package com.openbank.apimanagement.credential;

import java.time.Instant;

public record CredentialResponse(
        Long id,
        Long applicationId,
        String clientId,
        Instant createdAt,
        Instant updatedAt
) {

    public static CredentialResponse from(Credential credential) {
        return new CredentialResponse(
                credential.getId(),
                credential.getApplication().getId(),
                credential.getClientId(),
                credential.getCreatedAt(),
                credential.getUpdatedAt()
        );
    }
}