package com.openbank.apimanagement.exception;

public class ContextPathAlreadyExistsException extends RuntimeException {

    public ContextPathAlreadyExistsException(String contextPath) {
        super("An API with context path " + contextPath + " already exists");
    }
}