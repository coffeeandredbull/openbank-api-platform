package com.openbank.gateway.ratelimit;

public interface RateLimitService {

    Decision evaluate(long userId, String contextPath, String version, RateLimitPolicy policy);

    Decision evaluateForApplication(long applicationId, String contextPath, String version, RateLimitPolicy policy);

    enum State {
        ALLOWED, DENIED, UNAVAILABLE
    }

    record Decision(State state, long retryAfterSeconds) {

        public static Decision allowed() {
            return new Decision(State.ALLOWED, 0);
        }

        public static Decision denied(long retryAfterSeconds) {
            return new Decision(State.DENIED, retryAfterSeconds);
        }

        public static Decision unavailable() {
            return new Decision(State.UNAVAILABLE, 0);
        }
    }
}