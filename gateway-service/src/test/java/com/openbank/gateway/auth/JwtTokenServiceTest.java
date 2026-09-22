package com.openbank.gateway.auth;

import com.openbank.gateway.GatewayTestJwt;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final String SECRET = GatewayTestJwt.SECRET;
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Clock CLOCK_ONE_HOUR_LATER = Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC);

    private final JwtTokenService service = new JwtTokenService(SECRET, CLOCK);

    @Test
    void validAdminTokenIsAccepted() {
        JwtIdentity identity = service.validateToken(GatewayTestJwt.admin());
        assertThat(identity.userId()).isEqualTo(1L);
        assertThat(identity.role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void validDeveloperTokenIsAccepted() {
        JwtIdentity identity = service.validateToken(GatewayTestJwt.developer());
        assertThat(identity.userId()).isEqualTo(2L);
        assertThat(identity.role()).isEqualTo(UserRole.DEVELOPER);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtTokenService laterService = new JwtTokenService(SECRET, CLOCK_ONE_HOUR_LATER);
        assertThatThrownBy(() -> laterService.validateToken(GatewayTestJwt.token("ADMIN", "1", -3600, NOW)))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtTokenService otherService = new JwtTokenService(
                "a-completely-different-secret-value-that-is-long-enough", CLOCK);
        assertThatThrownBy(() -> otherService.validateToken(GatewayTestJwt.admin()))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void tamperedSignatureIsRejected() {
        assertThatThrownBy(() -> service.validateToken(GatewayTestJwt.tampered(GatewayTestJwt.admin())))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void malformedTokenIsRejected() {
        assertThatThrownBy(() -> service.validateToken("not.a.jwt"))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void tokenWithoutSubjectIsRejected() {
        assertThatThrownBy(() -> service.validateToken(GatewayTestJwt.missingSub()))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void tokenWithoutRoleClaimIsRejected() {
        assertThatThrownBy(() -> service.validateToken(GatewayTestJwt.missingRole()))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void tokenWithUnknownRoleIsRejected() {
        assertThatThrownBy(() -> service.validateToken(GatewayTestJwt.invalidRole()))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void nonNumericSubjectIsRejected() {
        assertThatThrownBy(() -> service.validateToken(GatewayTestJwt.token("ADMIN", "not-a-number", 3600)))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void constructorFailsFastWhenSecretIsMissing() {
        assertThatThrownBy(() -> new JwtTokenService(null, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.secret")
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void constructorFailsFastWhenSecretIsBlankOrTooShort() {
        assertThatThrownBy(() -> new JwtTokenService("", CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
        assertThatThrownBy(() -> new JwtTokenService("much-too-short", CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
    }
}