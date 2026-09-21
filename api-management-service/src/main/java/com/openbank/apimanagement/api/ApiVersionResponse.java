package com.openbank.apimanagement.api;

import java.time.Instant;

public record ApiVersionResponse(
        Long id,
        Long apiId,
        String version,
        Instant createdAt,
        Instant updatedAt
) {

    public static ApiVersionResponse from(ApiVersion apiVersion) {
        return new ApiVersionResponse(
                apiVersion.getId(),
                apiVersion.getApi().getId(),
                apiVersion.getVersion(),
                apiVersion.getCreatedAt(),
                apiVersion.getUpdatedAt()
        );
    }
}