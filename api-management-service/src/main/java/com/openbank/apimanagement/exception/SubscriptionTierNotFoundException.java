package com.openbank.apimanagement.exception;

public class SubscriptionTierNotFoundException extends RuntimeException {

    public SubscriptionTierNotFoundException(Long id) {
        super("Subscription tier with id " + id + " does not exist");
    }
}