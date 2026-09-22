package com.openbank.apimanagement.exception;

public class SubscriptionAlreadyExistsException extends RuntimeException {

    public SubscriptionAlreadyExistsException(Long applicationId, Long apiVersionId) {
        super("Application " + applicationId + " is already subscribed to api version " + apiVersionId);
    }
}