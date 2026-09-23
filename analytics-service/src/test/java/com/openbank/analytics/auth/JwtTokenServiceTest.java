package com.openbank.analytics.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.analytics.config.JwtProperties;
import com.openbank.analytics.exception.InvalidJwtException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final String SECRET = "test-jwt-secret-value-with-at-least-256-bits-1234";
    private static final String OTHER_SECRET = "a-completely-different-test-secret-9876543210-abcd";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long EXPIRATION_SECONDS = 3600L;

    private final JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET), CLOCK);

    @Test
    void validatesSignatureExpiryAndClaims() throws Exception {
        String token = sign(claims("123", "DEVELOPER", NOW.plusSeconds(EXPIRATION_SECONDS)));

        JwtIdentity identity = service.validateToken(token);

        assertThat(identity.userId()).isEqualTo(123L);
        assertThat(identity.role()).isEqualTo(UserRole.DEVELOPER);
    }

    @Test
    void unknownRoleMapsToNullRoleInsteadOfFailingAuthentication() throws Exception {
        String token = sign(claims("123", "CUSTOMER", NOW.plusSeconds(EXPIRATION_SECONDS)));

        JwtIdentity identity = service.validateToken(token);

        assertThat(identity.userId()).isEqualTo(123L);
        assertThat(identity.role()).isNull();
    }

    @Test
    void rejectsMalformedTokens() {
        assertThatThrownBy(() -> service.validateToken("not-a-jwt"))
                .isInstanceOf(InvalidJwtException.class);
        assertThatThrownBy(() -> service.validateToken(""))
                .isInstanceOf(InvalidJwtException.class);
        assertThatThrownBy(() -> service.validateToken("eyJhbGciOiJIUzI1NiJ9.only"))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        String token = sign(claims("123", "DEVELOPER", NOW.plusSeconds(EXPIRATION_SECONDS)));

        String[] parts = token.split("\\.");
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? "B" : "A") + parts[2].substring(1);

        assertThatThrownBy(() -> service.validateToken(String.join(".", parts)))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder()
                        .subject("123")
                        .claim("role", "DEVELOPER")
                        .expirationTime(Date.from(NOW.plusSeconds(EXPIRATION_SECONDS)))
                        .build());
        jwt.sign(new MACSigner(OTHER_SECRET.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> service.validateToken(jwt.serialize()))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        JwtTokenService later = new JwtTokenService(
                new JwtProperties(SECRET),
                Clock.fixed(NOW.plusSeconds(EXPIRATION_SECONDS * 2), ZoneOffset.UTC));
        String token = sign(claims("123", "DEVELOPER", NOW.plusSeconds(EXPIRATION_SECONDS)));

        assertThatThrownBy(() -> later.validateToken(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsTokenMissingRequiredClaims() throws Exception {
        String missingSubject = sign(claims(null, "DEVELOPER", NOW.plusSeconds(EXPIRATION_SECONDS)));
        String missingRole = sign(claims("123", null, NOW.plusSeconds(EXPIRATION_SECONDS)));
        String missingExpiration = sign(claims("123", "DEVELOPER", null));

        assertThatThrownBy(() -> service.validateToken(missingSubject))
                .isInstanceOf(InvalidJwtException.class);
        assertThatThrownBy(() -> service.validateToken(missingRole))
                .isInstanceOf(InvalidJwtException.class);
        assertThatThrownBy(() -> service.validateToken(missingExpiration))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsNonNumericSubject() throws Exception {
        String token = sign(claims("123-abc", "DEVELOPER", NOW.plusSeconds(EXPIRATION_SECONDS)));

        assertThatThrownBy(() -> service.validateToken(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void constructorFailsFastWhenSecretIsMissingOrTooShort() {
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties(""), CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.secret");
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties(null), CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.secret");
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties("much-too-short"), CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.secret");
    }

    private static JWTClaimsSet.Builder claims(String subject, String role, Instant expiration) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        if (subject != null) {
            builder.subject(subject);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        if (expiration != null) {
            builder.expirationTime(Date.from(expiration));
        }
        return builder;
    }

    private String sign(JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                claims.build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}