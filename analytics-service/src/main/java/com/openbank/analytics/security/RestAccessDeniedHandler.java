package com.openbank.analytics.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.analytics.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * 403 response for authenticated requests that hold no required authority
 * (Phase 23, Slice 5), including valid JWTs whose role claim this platform
 * does not recognize. Reuses the project's stable error shape.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    public static final String CODE = "ACCESS_DENIED";
    public static final String MESSAGE = "You do not have permission to access this resource";

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        GlobalExceptionHandler.ErrorResponse body = new GlobalExceptionHandler.ErrorResponse(
                Instant.now(),
                403,
                "Forbidden",
                request.getRequestURI(),
                CODE,
                MESSAGE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}