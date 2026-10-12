package com.openbank.apimanagement.exception;

public class MalformedPersistedScopesException extends RuntimeException {

    public MalformedPersistedScopesException(Throwable cause) {
        super("Persisted scope data is malformed and cannot be evaluated", cause);
    }
}
