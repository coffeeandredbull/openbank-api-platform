package com.openbank.gateway.filter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class ScopePolicyParser {

    static final int MAX_SCOPES = 20;
    static final int MAX_SCOPE_LENGTH = 64;

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*(?::[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*)?$");

    private ScopePolicyParser() {
    }

    static List<String> parse(String canonicalScopes) {
        if (canonicalScopes == null) {
            return null;
        }
        if (canonicalScopes.isEmpty()) {
            return List.of();
        }
        String[] tokens = canonicalScopes.split(" ", -1);
        if (tokens.length > MAX_SCOPES) {
            return null;
        }
        Set<String> unique = new HashSet<>();
        for (String token : tokens) {
            if (token.length() > MAX_SCOPE_LENGTH || !TOKEN_PATTERN.matcher(token).matches()) {
                return null;
            }
            if (!unique.add(token)) {
                return null;
            }
        }
        return List.of(tokens);
    }
}
