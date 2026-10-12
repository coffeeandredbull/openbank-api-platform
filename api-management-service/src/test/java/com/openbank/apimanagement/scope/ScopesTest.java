package com.openbank.apimanagement.scope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopesTest {

    @Test
    void canonicalizeTreatsNullAndEmptyAsUnrestricted() {
        assertThat(Scopes.canonicalize(null)).isEmpty();
        assertThat(Scopes.canonicalize(List.of())).isEmpty();
        assertThat(Scopes.UNRESTRICTED).isEmpty();
    }

    @Test
    void canonicalizeJoinsTokensWithASingleSpaceInInputOrder() {
        assertThat(Scopes.canonicalize(List.of("payments:read", "orders.write", "openid")))
                .isEqualTo("payments:read orders.write openid");
    }

    @Test
    void canonicalizeRejectsDuplicateTokens() {
        assertThatThrownBy(() -> Scopes.canonicalize(List.of("payments:read", "payments:read")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate scope token");
    }

    @Test
    void canonicalizeRejectsTooManyTokens() {
        List<String> tokens = java.util.stream.IntStream.range(0, Scopes.MAX_SCOPES + 1)
                .mapToObj(index -> "scope" + index)
                .toList();

        assertThatThrownBy(() -> Scopes.canonicalize(tokens))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(Scopes.MAX_SCOPES));
    }

    @Test
    void canonicalizeRejectsOverlongToken() {
        assertThatThrownBy(() -> Scopes.canonicalize(List.of("a".repeat(Scopes.MAX_SCOPE_LENGTH + 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid scope token");
    }

    @Test
    void canonicalizeRejectsNullToken() {
        assertThatThrownBy(() -> Scopes.canonicalize(java.util.Arrays.asList("payments:read", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid scope token");
    }

    @Test
    void canonicalizeAcceptsLowercaseTokensWithASingleOptionalColon() {
        assertThat(Scopes.canonicalize(List.of(
                "accounts", "a", "openid", "accounts.read", "accounts-read", "accounts_read",
                "accounts:read", "accounts:read.v2", "scope1:a1-b2")))
                .isEqualTo(
                        "accounts a openid accounts.read accounts-read accounts_read "
                                + "accounts:read accounts:read.v2 scope1:a1-b2");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Accounts", "1accounts", "-accounts", "_accounts", ":accounts", "accounts:",
            "accounts::read", "accounts..read", "accounts.-read", "accounts:Read", "accounts read",
            "accounts:read:write", " accounts", " "})
    void malformedTokensAreRejected(String token) {
        assertThatThrownBy(() -> Scopes.canonicalize(List.of(token)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseSplitsTheCanonicalForm() {
        assertThat(Scopes.parse("payments:read orders.write")).containsExactly("payments:read", "orders.write");
        assertThat(Scopes.parse("")).isEmpty();
    }

    @Test
    void parseRejectsNull() {
        assertThatThrownBy(() -> Scopes.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsMalformedCanonicalForm() {
        assertThatThrownBy(() -> Scopes.parse("payments:read payments:read"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate scope token");
        assertThatThrownBy(() -> Scopes.parse("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effectiveIsUnrestrictedWhenNeitherSideIsRestricted() {
        assertThat(Scopes.effective("", "")).isEmpty();
    }

    @Test
    void effectiveReturnsTheCredentialScopesWhenTheTierIsUnrestricted() {
        assertThat(Scopes.effective("payments:read", "")).isEqualTo("payments:read");
    }

    @Test
    void effectiveReturnsTheTierScopesWhenTheCredentialIsUnrestricted() {
        assertThat(Scopes.effective("", "payments:read")).isEqualTo("payments:read");
    }

    @Test
    void effectiveIntersectsBothSidesPreservingCredentialOrder() {
        assertThat(Scopes.effective("orders:write payments:read", "payments:read payments:write"))
                .isEqualTo("payments:read");
    }

    @Test
    void effectiveIsEmptyWhenTheIntersectionIsEmpty() {
        assertThat(Scopes.effective("orders:read", "payments:read")).isEmpty();
    }

    @Test
    void isRestrictedDistinguishesEmptyFromScoped() {
        assertThat(Scopes.isRestricted(null)).isFalse();
        assertThat(Scopes.isRestricted("")).isFalse();
        assertThat(Scopes.isRestricted("a")).isTrue();
    }

    @Test
    void validatorAcceptsNullAndValidListsAndRejectsMalformedOnes() {
        ScopesValidator validator = new ScopesValidator();

        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid(List.of("payments:read"), null)).isTrue();
        assertThat(validator.isValid(List.of("payments:read", "payments:read"), null)).isFalse();
        assertThat(validator.isValid(List.of("INVALID SCOPE"), null)).isFalse();
        assertThat(validator.isValid(List.of("a".repeat(65)), null)).isFalse();
    }
}
