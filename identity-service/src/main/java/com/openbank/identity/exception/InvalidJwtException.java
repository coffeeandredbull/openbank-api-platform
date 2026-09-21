package com.openbank.identity.exception;

public class InvalidJwtException extends RuntimeException {

    public static final String MESSAGE = "Access token is invalid or expired";

    public InvalidJwtException() {
        super(MESSAGE);
    }
}