package com.openbank.gateway.ratelimit;

import org.springframework.stereotype.Component;

@Component
public class RateLimitKeyGenerator {

    public String keyFor(long userId, String contextPath, String version) {
        return "rate_limit:" + userId + ":" + contextPath + ":" + version;
    }

    public String keyForApplication(long applicationId, String contextPath, String version) {
        return "rate_limit:app:" + applicationId + ":" + contextPath + ":" + version;
    }
}