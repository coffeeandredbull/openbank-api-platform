package com.openbank.apimanagement.exception;

public class SubscriptionTierInactiveException extends RuntimeException {

    public SubscriptionTierInactiveException(Long id) {
        super("Subscription tier with id " + id + " is not active");
    }
}
