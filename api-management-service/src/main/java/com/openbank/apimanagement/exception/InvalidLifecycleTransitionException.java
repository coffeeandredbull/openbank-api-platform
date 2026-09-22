package com.openbank.apimanagement.exception;

import com.openbank.apimanagement.api.ApiVersionLifecycle;

public class InvalidLifecycleTransitionException extends RuntimeException {

    public InvalidLifecycleTransitionException(ApiVersionLifecycle from, ApiVersionLifecycle to) {
        super("Lifecycle transition from " + from + " to " + to + " is not allowed");
    }
}