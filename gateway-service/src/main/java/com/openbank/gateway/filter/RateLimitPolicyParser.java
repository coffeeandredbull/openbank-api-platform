package com.openbank.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.openbank.gateway.ratelimit.RateLimitPolicy;

final class RateLimitPolicyParser {

    private RateLimitPolicyParser() {
    }

    static RateLimitPolicy parse(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode requests = node.path("requestsPerWindow");
        JsonNode window = node.path("windowSeconds");
        if (!requests.isIntegralNumber() || !window.isIntegralNumber()) {
            return null;
        }
        try {
            return new RateLimitPolicy(requests.asInt(), window.asInt());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}