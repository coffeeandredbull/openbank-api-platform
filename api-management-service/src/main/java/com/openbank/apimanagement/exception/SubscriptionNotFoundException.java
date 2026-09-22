package com.openbank.apimanagement.exception;

public class SubscriptionNotFoundException extends RuntimeException {

    public SubscriptionNotFoundException(Long id) {
        super("Subscription with id " + id + " does not exist");
    }
}