package com.openbank.identity.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.identity.config.JwtProperties;
import com.openbank.identity.exception.InvalidJwtException;
import com.openbank.identity.user.UserRole;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenService {

    private static final int MIN_SECRET_BYTES = 32;
    private static final JWSAlgorithm ALGORITHM = JWSAlgorithm.HS256;

    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final MACSigner signer;
    private final MACVerifier verifier;

    public JwtTokenService(JwtProperties jwtProperties, Clock clock) {
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        byte[] secretBytes = jwtProperties.secret() == null
                ? new byte[0]
                : jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret (environment variable JWT_SECRET) must be set to a random value of at least 256 bits");
        }
        if (jwtProperties.expirationSeconds() <= 0) {
            throw new IllegalStateException(
                    "app.jwt.expiration-seconds (environment variable JWT_EXPIRATION_SECONDS) must be positive");
        }
        try {
            this.signer = new MACSigner(secretBytes);
            this.verifier = new MACVerifier(secretBytes);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to initialize JWT signing", e);
        }
    }

    public String generateAccessToken(Long userId, UserRole role) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(jwtProperties.expirationSeconds());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .claim("role", role.name())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(ALGORITHM).type(JOSEObjectType.JWT).build(),
                claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign access token", e);
        }
        return jwt.serialize();
    }

    public long expiresInSeconds() {
        return jwtProperties.expirationSeconds();
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