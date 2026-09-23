package com.openbank.analytics.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.analytics.config.JwtProperties;
import com.openbank.analytics.exception.InvalidJwtException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.util.Date;

/**
 * Verifies HS256 access tokens issued by the Identity Service (Phase 23,
 * Slice 5). Only validation is implemented — this service never signs tokens
 * (it is not a second token issuer). A token is accepted only when the
 * signature, the expiry, and the subject claim are all valid; the {@code role}
 * claim must be present, and a role the platform does not recognize maps to
 * no authority rather than to an authentication failure.
 */
@Service
public class JwtTokenService {

    private static final int MIN_SECRET_BYTES = 32;

    private final Clock clock;
    private final MACVerifier verifier;

    public JwtTokenService(JwtProperties jwtProperties, Clock clock) {
        this.clock = clock;
        byte[] secretBytes = jwtProperties.secret() == null
                ? new byte[0]
                : jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret (environment variable JWT_SECRET) must be set to a random value of at least 256 bits");
        }
        try {
            this.verifier = new MACVerifier(secretBytes);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to initialize JWT verification", e);
        }
    }

    public JwtIdentity validateToken(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(verifier)) {
                throw new InvalidJwtException();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiration = claims.getExpirationTime();
            if (expiration == null || !expiration.toInstant().isAfter(clock.instant())) {
                throw new InvalidJwtException();
            }
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new InvalidJwtException();
            }
            String roleClaim = claims.getStringClaim("role");
            if (roleClaim == null || roleClaim.isBlank()) {
                throw new InvalidJwtException();
            }
            return new JwtIdentity(Long.parseLong(subject), parseRole(roleClaim));
        } catch (ParseException | JOSEException | NumberFormatException e) {
            throw new InvalidJwtException();
        }
    }

    private static UserRole parseRole(String roleClaim) {
        for (UserRole role : UserRole.values()) {
            if (role.name().equals(roleClaim)) {
                return role;
            }
        }
        return null;
    }
}
