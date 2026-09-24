package com.openbank.apimanagement.credential;

import com.openbank.apimanagement.subscription.SubscriptionPolicy;

public record CredentialCheckResponse(
        boolean authenticated,
        Long applicationId,
        Long ownerUserId,
        boolean subscribed,
        Long tierId,
        String tierName,
        Integer requestsPerWindow,
        Integer windowSeconds) {

    public static CredentialCheckResponse unauthorized() {
        return new CredentialCheckResponse(false, null, null, false, null, null, null, null);
    }

    public static CredentialCheckResponse authenticated(
            Long applicationId, Long ownerUserId, SubscriptionPolicy policy) {
        if (policy == null) {
            return new CredentialCheckResponse(true, applicationId, ownerUserId, false, null, null, null, null);
        }
        return new CredentialCheckResponse(
                true,
                applicationId,
                ownerUserId,
                true,
                policy.tierId(),
                policy.tierName(),
                policy.requestsPerWindow(),
                policy.windowSeconds());
    }
}