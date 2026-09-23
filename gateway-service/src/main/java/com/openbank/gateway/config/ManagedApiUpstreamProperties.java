package com.openbank.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

@Component
public class ManagedApiUpstreamProperties {

    private final String targetUrl;

    public ManagedApiUpstreamProperties(
            @Value("${MANAGED_API_TARGET_URL:http://localhost:8084}") String value) {
        validate(value);
        this.targetUrl = value;
    }

    public String targetUrl() {
        return targetUrl;
    }

    private static void validate(String value) {
        if (value == null || value.isBlank() || containsWhitespace(value)) {
            throw invalidConfiguration();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalidConfiguration();
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalidConfiguration();
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw invalidConfiguration();
        }
        if (uri.getUserInfo() != null) {
            throw invalidConfiguration();
        }
        if (uri.getFragment() != null) {
            throw invalidConfiguration();
        }
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException(
                "MANAGED_API_TARGET_URL must be an absolute http(s) URL that has a host and contains "
                        + "no credentials, fragment, or whitespace; refusing to start");
    }
}