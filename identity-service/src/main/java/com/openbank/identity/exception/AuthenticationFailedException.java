package com.openbank.identity.exception;

public class AuthenticationFailedException extends RuntimeException {

    public static final String MESSAGE = "Invalid email or password";

    public AuthenticationFailedException() {
        super(MESSAGE);
    }
}