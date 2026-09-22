package com.openbank.payment.exception;

public class AccountAlreadyExistsException extends RuntimeException {

    public AccountAlreadyExistsException(Long ownerUserId) {
        super("Account for user " + ownerUserId + " already exists");
    }
}