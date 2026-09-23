package com.openbank.analytics.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Authenticates internal service-to-service requests (Phase 23, Slice 4). It
 * guards the {@code /internal/**} path with a shared token carried in
 * {@link InternalApiAuthProperties#INTERNAL_TOKEN_HEADER}; comparison is done
 * over SHA-256 digests with a constant-time check. The filter fails closed:
 * when no token is configured, every internal request is rejected.
 */
public class InternalApiAuthenticationFilter extends OncePerRequestFilter {

    private final InternalApiAuthProperties properties;

    public InternalApiAuthenticationFilter(InternalApiAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isAuthorized(request)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAuthorized(HttpServletRequest request) {
        String configured = properties.analyticsToken();
        if (configured.isBlank()) {
            return false;
        }
        String supplied = request.getHeader(InternalApiAuthProperties.INTERNAL_TOKEN_HEADER);
        return supplied != null && constantTimeEquals(configured, supplied);
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(
                    digest.digest(expected.getBytes(StandardCharsets.UTF_8)),
                    digest.digest(supplied.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}