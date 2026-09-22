package com.openbank.payment.exception;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long id) {
        super("Account with id " + id + " does not exist");
    }
}