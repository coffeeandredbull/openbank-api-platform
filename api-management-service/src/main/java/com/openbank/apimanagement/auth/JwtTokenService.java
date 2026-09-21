package com.openbank.apimanagement.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.apimanagement.config.JwtProperties;
import com.openbank.apimanagement.exception.InvalidJwtException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.util.Date;

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
            if (roleClaim == null) {
                throw new InvalidJwtException();
            }
            return new JwtIdentity(Long.parseLong(subject), UserRole.valueOf(roleClaim));
        } catch (ParseException | JOSEException | IllegalArgumentException e) {
            throw new InvalidJwtException();
        }
    }
}