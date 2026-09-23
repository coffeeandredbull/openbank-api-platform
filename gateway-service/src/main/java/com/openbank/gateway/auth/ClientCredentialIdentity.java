package com.openbank.gateway.auth;

public record ClientCredentialIdentity(String clientId, Long applicationId, Long ownerUserId) {
}