package com.openbank.identity.auth;

import com.openbank.identity.exception.InvalidJwtException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/revoke")
    public RevokeResponse revoke(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return authService.revoke(extractBearerToken(authorization));
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new InvalidJwtException();
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new InvalidJwtException();
        }
        return token;
    }
}