package com.openbank.analytics.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.analytics.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * 401 response for unauthenticated requests (Phase 23, Slice 5). It reuses the
 * project's stable error shape and never discloses which JWT check failed or
 * any token material.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String CODE = "UNAUTHENTICATED";
    public static final String MESSAGE = "Authentication is required";

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        GlobalExceptionHandler.ErrorResponse body = new GlobalExceptionHandler.ErrorResponse(
                Instant.now(),
                401,
                "Unauthorized",
                request.getRequestURI(),
                CODE,
                MESSAGE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}