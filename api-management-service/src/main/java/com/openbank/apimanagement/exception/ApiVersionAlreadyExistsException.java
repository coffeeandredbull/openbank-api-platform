package com.openbank.apimanagement.exception;

public class ApiVersionAlreadyExistsException extends RuntimeException {

    public ApiVersionAlreadyExistsException(Long apiId, String version) {
        super("Api version " + version + " already exists for api " + apiId);
    }
}