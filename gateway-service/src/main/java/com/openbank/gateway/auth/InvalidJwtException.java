package com.openbank.gateway.auth;

public class InvalidJwtException extends RuntimeException {

    public InvalidJwtException() {
        super("Access token is invalid or expired");
    }
}