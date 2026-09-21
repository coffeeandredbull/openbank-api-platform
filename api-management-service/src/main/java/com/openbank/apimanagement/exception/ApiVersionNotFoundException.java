package com.openbank.apimanagement.exception;

public class ApiVersionNotFoundException extends RuntimeException {

    public ApiVersionNotFoundException(Long apiId, Long versionId) {
        super("Api version with id " + versionId + " does not exist for api " + apiId);
    }
}