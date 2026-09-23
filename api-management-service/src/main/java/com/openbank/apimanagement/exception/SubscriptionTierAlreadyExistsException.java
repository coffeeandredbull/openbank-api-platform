package com.openbank.apimanagement.exception;

public class SubscriptionTierAlreadyExistsException extends RuntimeException {

    public SubscriptionTierAlreadyExistsException(String name) {
        super("A subscription tier with name '" + name + "' already exists");
    }
}