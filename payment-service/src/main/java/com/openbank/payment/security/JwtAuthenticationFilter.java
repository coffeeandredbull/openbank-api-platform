package com.openbank.payment.security;

import com.openbank.payment.auth.JwtIdentity;
import com.openbank.payment.auth.JwtTokenService;
import com.openbank.payment.exception.InvalidJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader(AUTHORIZATION_HEADER);
            if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                String token = header.substring(BEARER_PREFIX.length()).trim();
                if (!token.isEmpty()) {
                    try {
                        JwtIdentity identity = jwtTokenService.validateToken(token);
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                identity,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + identity.role().name())));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } catch (InvalidJwtException ignored) {
                        SecurityContextHolder.clearContext();
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}