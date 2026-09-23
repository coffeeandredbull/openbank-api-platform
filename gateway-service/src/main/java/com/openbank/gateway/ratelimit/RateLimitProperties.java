package com.openbank.gateway.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RateLimitProperties {

    private final int requests;
    private final int windowSeconds;

    public RateLimitProperties(
            @Value("${RATE_LIMIT_REQUESTS:100}") int requests,
            @Value("${RATE_LIMIT_WINDOW_SECONDS:60}") int windowSeconds) {
        if (requests <= 0) {
            throw new IllegalStateException(
                    "RATE_LIMIT_REQUESTS must be a positive integer, got " + requests);
        }
        if (windowSeconds <= 0) {
            throw new IllegalStateException(
                    "RATE_LIMIT_WINDOW_SECONDS must be a positive integer, got " + windowSeconds);
        }
        this.requests = requests;
        this.windowSeconds = windowSeconds;
    }

    public int requests() {
        return requests;
    }

    public int windowSeconds() {
        return windowSeconds;
    }
}