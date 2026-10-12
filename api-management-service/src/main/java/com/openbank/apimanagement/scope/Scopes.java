package com.openbank.apimanagement.scope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class Scopes {

    public static final String UNRESTRICTED = "";
    public static final int MAX_SCOPES = 20;
    public static final int MAX_SCOPE_LENGTH = 64;

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*(?::[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*)?$");

    private Scopes() {
    }

    public static String canonicalize(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return UNRESTRICTED;
        }
        if (scopes.size() > MAX_SCOPES) {
            throw new IllegalArgumentException("scopes must not exceed " + MAX_SCOPES + " tokens");
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null || !isValidToken(scope)) {
                throw new IllegalArgumentException("invalid scope token");
            }
            if (!tokens.add(scope)) {
                throw new IllegalArgumentException("duplicate scope token");
            }
        }
        return String.join(" ", tokens);
    }

    public static List<String> parse(String canonicalScopes) {
        if (canonicalScopes == null) {
            throw new IllegalArgumentException("scopes must not be null");
        }
        if (canonicalScopes.isEmpty()) {
            return List.of();
        }
        String[] tokens = canonicalScopes.split(" ", -1);
        if (tokens.length > MAX_SCOPES) {
            throw new IllegalArgumentException("scopes must not exceed " + MAX_SCOPES + " tokens");
        }
        Set<String> unique = new HashSet<>();
        for (String token : tokens) {
            if (!isValidToken(token)) {
                throw new IllegalArgumentException("invalid scope token");
            }
            if (!unique.add(token)) {
                throw new IllegalArgumentException("duplicate scope token");
            }
        }
        return List.of(tokens);
    }

    public static String effective(String credentialScopes, String tierScopes) {
        boolean credentialRestricted = isRestricted(credentialScopes);
        boolean tierRestricted = isRestricted(tierScopes);
        if (!credentialRestricted && !tierRestricted) {
            return UNRESTRICTED;
        }
        if (credentialRestricted && !tierRestricted) {
            return credentialScopes;
        }
        if (!credentialRestricted && tierRestricted) {
            return tierScopes;
        }
        Set<String> tierTokens = new HashSet<>(parse(tierScopes));
        List<String> intersection = new ArrayList<>();
        for (String token : parse(credentialScopes)) {
            if (tierTokens.contains(token)) {
                intersection.add(token);
            }
        }
        return String.join(" ", intersection);
    }

    public static boolean isRestricted(String canonicalScopes) {
        return canonicalScopes != null && !canonicalScopes.isEmpty();
    }

    private static boolean isValidToken(String token) {
        return token.length() <= MAX_SCOPE_LENGTH && TOKEN_PATTERN.matcher(token).matches();
    }
}
