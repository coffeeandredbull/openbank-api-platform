package com.openbank.apimanagement.credential;

import java.time.Instant;

public record CredentialCreatedResponse(
        Long id,
        Long applicationId,
        String clientId,
        String clientSecret,
        Instant createdAt,
        Instant updatedAt
) {

    public static CredentialCreatedResponse from(Credential credential, String plaintextClientSecret) {
        return new CredentialCreatedResponse(
                credential.getId(),
                credential.getApplication().getId(),
                credential.getClientId(),
                plaintextClientSecret,
                credential.getCreatedAt(),
                credential.getUpdatedAt()
        );
    }
}