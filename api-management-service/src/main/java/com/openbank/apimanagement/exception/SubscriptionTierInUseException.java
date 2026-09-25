package com.openbank.apimanagement.exception;

public class SubscriptionTierInUseException extends RuntimeException {

    public SubscriptionTierInUseException(Long id) {
        super("Subscription tier with id " + id + " is referenced by one or more subscriptions");
    }
}
