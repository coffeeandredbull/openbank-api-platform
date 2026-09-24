package com.openbank.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

public final class GatewayTestJwt {

    public static final String SECRET = "integration-test-jwt-secret-value-0123456789-ab";

    private GatewayTestJwt() {
    }

    public static String admin() {
        return token("ADMIN", "1", 3600);
    }

    public static String developer() {
        return token("DEVELOPER", "2", 3600);
    }

    public static String adminWithJti(String jti) {
        return tokenWithJti("ADMIN", "1", 3600, Instant.now(), jti);
    }

    public static String developerWithJti(String jti) {
        return tokenWithJti("DEVELOPER", "2", 3600, Instant.now(), jti);
    }

    public static String expiredAdmin() {
        return token("ADMIN", "1", -3600);
    }

    public static String missingSub() {
        return token("ADMIN", null, 3600);
    }

    public static String missingRole() {
        return token(null, "1", 3600);
    }

    public static String invalidRole() {
        return token("ROOT", "1", 3600);
    }

    public static String token(String role, String sub, long ttlSeconds) {
        return token(role, sub, ttlSeconds, Instant.now());
    }

    public static String token(String role, String sub, long ttlSeconds, Instant issuedAt) {
        return tokenWithJti(role, sub, ttlSeconds, issuedAt, null);
    }

    public static String tokenWithJti(String role, String sub, long ttlSeconds, Instant issuedAt, String jti) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
            if (jti != null) {
                claims.jwtID(jti);
            }
            if (sub != null) {
                claims.subject(sub);
            }
            if (role != null) {
                claims.claim("role", role);
            }
            claims.issueTime(Date.from(issuedAt));
            claims.expirationTime(Date.from(issuedAt.plusSeconds(ttlSeconds)));
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to build test jwt", e);
        }
    }

    public static String tampered(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        char original = token.charAt(signatureStart);
        char replacement = original == 'A' ? 'B' : 'A';
        return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
    }
}