package com.openbank.apimanagement.application;

import java.time.Instant;

public record ApplicationResponse(
        Long id,
        String name,
        String description,
        Long ownerUserId,
        Instant createdAt,
        Instant updatedAt
) {

    public static ApplicationResponse from(Application application) {
        return new ApplicationResponse(
                application.getId(),
                application.getName(),
                application.getDescription(),
                application.getOwnerUserId(),
                application.getCreatedAt(),
                application.getUpdatedAt()
        );
    }
}