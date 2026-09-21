package com.openbank.apimanagement.exception;

public class ApiNotFoundException extends RuntimeException {

    public ApiNotFoundException(Long id) {
        super("Api with id " + id + " does not exist");
    }
}