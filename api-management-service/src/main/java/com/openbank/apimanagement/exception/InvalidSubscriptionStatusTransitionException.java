package com.openbank.apimanagement.exception;

import com.openbank.apimanagement.subscription.SubscriptionStatus;

public class InvalidSubscriptionStatusTransitionException extends RuntimeException {

    public InvalidSubscriptionStatusTransitionException(SubscriptionStatus from, SubscriptionStatus to) {
        super("Subscription status transition from " + from + " to " + to + " is not allowed");
    }
}