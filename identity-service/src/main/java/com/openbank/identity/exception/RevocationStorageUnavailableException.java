package com.openbank.identity.exception;

public class RevocationStorageUnavailableException extends RuntimeException {

    public RevocationStorageUnavailableException() {
        super("The token revocation store is unavailable");
    }
}