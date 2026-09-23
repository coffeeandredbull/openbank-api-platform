package com.openbank.apimanagement.credential;

public record CredentialCheckResponse(
        boolean authenticated,
        Long applicationId,
        Long ownerUserId,
        boolean subscribed) {

    public static CredentialCheckResponse unauthorized() {
        return new CredentialCheckResponse(false, null, null, false);
    }

    public static CredentialCheckResponse authenticated(
            Long applicationId, Long ownerUserId, boolean subscribed) {
        return new CredentialCheckResponse(true, applicationId, ownerUserId, subscribed);
    }
}