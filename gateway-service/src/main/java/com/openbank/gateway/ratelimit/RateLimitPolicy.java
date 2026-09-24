package com.openbank.gateway.ratelimit;

public record RateLimitPolicy(int requestsPerWindow, int windowSeconds) {

    public RateLimitPolicy {
        if (requestsPerWindow <= 0) {
            throw new IllegalArgumentException("requestsPerWindow must be greater than 0");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be greater than 0");
        }
    }
}