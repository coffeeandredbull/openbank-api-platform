package com.openbank.apimanagement.exception;

import com.openbank.apimanagement.credential.CredentialStatus;

public class InvalidCredentialStatusTransitionException extends RuntimeException {

    public InvalidCredentialStatusTransitionException(CredentialStatus from, CredentialStatus to) {
        super("Credential status transition from " + from + " to " + to + " is not allowed");
    }

    public InvalidCredentialStatusTransitionException(Long credentialId, CredentialStatus status) {
        super("Credential with id " + credentialId + " is " + status + " and cannot be rotated");
    }
}