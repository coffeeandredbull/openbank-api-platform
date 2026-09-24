package com.openbank.apimanagement.credential;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public enum CredentialStatus {
    ACTIVE,
    REVOKED;

    private static final Map<CredentialStatus, Set<CredentialStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(CredentialStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(ACTIVE, Set.of(REVOKED));
        ALLOWED_TRANSITIONS.put(REVOKED, Set.of());
    }

    public boolean canTransitionTo(CredentialStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }
}