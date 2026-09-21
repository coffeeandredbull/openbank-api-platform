package com.openbank.identity.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.openbank.identity.config.JwtProperties;
import com.openbank.identity.exception.InvalidJwtException;
import com.openbank.identity.user.UserRole;
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

    private final JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET, EXPIRATION_SECONDS), CLOCK);

    @Test
    void generateAccessTokenProducesTokenWithExpectedClaims() throws Exception {
        String token = service.generateAccessToken(123L, UserRole.DEVELOPER);

        SignedJWT signed = SignedJWT.parse(token);
        assertThat(signed.getJWTClaimsSet().getSubject()).isEqualTo("123");
        assertThat(signed.getJWTClaimsSet().getStringClaim("role")).isEqualTo("DEVELOPER");
        assertThat(signed.getJWTClaimsSet().getExpirationTime()).isEqualTo(Date.from(NOW.plusSeconds(EXPIRATION_SECONDS)));
        assertThat(signed.getJWTClaimsSet().getIssueTime()).isEqualTo(Date.from(NOW));
        assertThat(signed.getJWTClaimsSet().toJSONObject())
                .doesNotContainKey("password")
                .doesNotContainKey("passwordHash");
        assertThat(token).doesNotContain("password").doesNotContain("passwordHash");

        JwtIdentity identity = service.validateToken(token);
        assertThat(identity.userId()).isEqualTo(123L);
        assertThat(identity.role()).isEqualTo(UserRole.DEVELOPER);
    }

    @Test
    void expiresInSecondsReflectsConfiguredExpiration() {
        assertThat(service.expiresInSeconds()).isEqualTo(EXPIRATION_SECONDS);
    }

    @Test
    void validateTokenRejectsMalformedTokens() {
        assertThatThrownBy(() -> service.validateToken("not-a-jwt"))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessage(InvalidJwtException.MESSAGE);
        assertThatThrownBy(() -> service.validateToken(""))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessage(InvalidJwtException.MESSAGE);
        assertThatThrownBy(() -> service.validateToken("eyJhbGciOiJIUzI1NiJ9.only"))

                .isInstanceOf(InvalidJwtException.class)
                .hasMessage(InvalidJwtException.MESSAGE);
    }

    @Test
    void validateTokenRejectsTamperedSignature() {
        String token = service.generateAccessToken(123L, UserRole.DEVELOPER);

        String[] parts = token.split("\\.");
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? "B" : "A") + parts[2].substring(1);
        String tampered = String.join(".", parts);

        assertThatThrownBy(() -> service.validateToken(tampered))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void validateTokenRejectsTokenSignedWithDifferentSecret() {
        JwtTokenService other = new JwtTokenService(new JwtProperties(OTHER_SECRET, EXPIRATION_SECONDS), CLOCK);
        String token = other.generateAccessToken(123L, UserRole.DEVELOPER);

        assertThatThrownBy(() -> service.validateToken(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void validateTokenRejectsExpiredToken() {
        JwtTokenService later = new JwtTokenService(
                new JwtProperties(SECRET, EXPIRATION_SECONDS),
                Clock.fixed(NOW.plusSeconds(EXPIRATION_SECONDS * 2), ZoneOffset.UTC));

        String token = service.generateAccessToken(123L, UserRole.DEVELOPER);

        assertThatThrownBy(() -> later.validateToken(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void validateTokenRejectsTokenMissingRequiredClaims() throws Exception {
        String missingSubject = sign(new JWTClaimsSet.Builder()
                .claim("role", "DEVELOPER")
                .expirationTime(Date.from(NOW.plusSeconds(EXPIRATION_SECONDS)))
                .build());
        String missingRole = sign(new JWTClaimsSet.Builder()
                .subject("123")
                .expirationTime(Date.from(NOW.plusSeconds(EXPIRATION_SECONDS)))
                .build());
        String missingExpiration = sign(new JWTClaimsSet.Builder()
                .subject("123")
                .claim("role", "DEVELOPER")
                .build());

        assertThatThrownBy(() -> service.validateToken(missingSubject))
                .isInstanceOf(InvalidJwtException.class);
        assertThatThrownBy(() -> service.validateToken(missingRole))
                .isInstanceOf(InvalidJwtException.class);
        assertThatThrownBy(() -> service.validateToken(missingExpiration))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void constructorFailsFastWhenSecretIsMissingOrTooShort() {
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties("", EXPIRATION_SECONDS), CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.secret");
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties(null, EXPIRATION_SECONDS), CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.secret");
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties("much-too-short", EXPIRATION_SECONDS), CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.secret");
    }

    @Test
    void constructorFailsFastWhenExpirationIsNotPositive() {
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties(SECRET, 0), CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expiration");
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties(SECRET, -1), CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expiration");
    }

    private String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}