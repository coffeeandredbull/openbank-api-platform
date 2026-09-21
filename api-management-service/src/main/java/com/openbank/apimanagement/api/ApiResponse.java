package com.openbank.apimanagement.api;

import java.time.Instant;

public record ApiResponse(
        Long id,
        String name,
        String description,
        String contextPath,
        Instant createdAt,
        Instant updatedAt
) {

    public static ApiResponse from(Api api) {
        return new ApiResponse(
                api.getId(),
                api.getName(),
                api.getDescription(),
                api.getContextPath(),
                api.getCreatedAt(),
                api.getUpdatedAt()
        );
    }
}