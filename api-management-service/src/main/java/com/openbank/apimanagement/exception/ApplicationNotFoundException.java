package com.openbank.apimanagement.exception;

public class ApplicationNotFoundException extends RuntimeException {

    public ApplicationNotFoundException(Long id) {
        super("Application with id " + id + " does not exist");
    }
}