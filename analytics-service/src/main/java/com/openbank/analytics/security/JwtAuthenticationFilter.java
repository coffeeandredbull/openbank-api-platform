package com.openbank.analytics.security;

import com.openbank.analytics.auth.JwtIdentity;
import com.openbank.analytics.auth.JwtTokenService;
import com.openbank.analytics.exception.InvalidJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates bearer JWTs on public queries (Phase 23, Slice 5). A token is
 * accepted only when the existing {@link JwtTokenService} verifies it; the
 * caller identity (principal) is then the verified {@link JwtIdentity} and the
 * authorities mirror the role claim ({@code ROLE_ADMIN}/{@code ROLE_DEVELOPER}).
 * A token whose role the platform does not recognize is still treated as
 * authenticated but carries no authority, so authorization rejects it. Any
 * other token failure clears the context and leaves the request anonymous,
 * which the security chain answers with 401. The token itself is never
 * logged, stored, or placed in the security context.
 */
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
                        List<GrantedAuthority> authorities = identity.role() == null
                                ? List.of()
                                : List.of(new SimpleGrantedAuthority("ROLE_" + identity.role().name()));
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                identity, null, authorities);
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