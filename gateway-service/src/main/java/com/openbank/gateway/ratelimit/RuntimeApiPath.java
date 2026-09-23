package com.openbank.gateway.ratelimit;

import java.util.Optional;

public record RuntimeApiPath(String contextPath, String version) {

    private static final String RUNTIME_API_PATH = "/runtime/apis";

    public static Optional<RuntimeApiPath> from(String path) {
        if (path == null || !path.startsWith(RUNTIME_API_PATH + "/")) {
            return Optional.empty();
        }
        String remainder = path.substring(RUNTIME_API_PATH.length() + 1);
        String[] segments = remainder.split("/");
        if (segments.length < 2) {
            return Optional.empty();
        }
        String context = segments[0];
        String version = segments[1];
        if (context.isEmpty() || version.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeApiPath("/" + context, version));
    }
}