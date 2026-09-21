package com.openbank.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openbank.identity.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

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
                MESSAGE,
                Map.of());
        objectMapper.writeValue(response.getWriter(), body);
    }
}