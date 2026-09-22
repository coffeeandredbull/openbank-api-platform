package com.openbank.payment.exception;

public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(Long id) {
        super("Transaction with id " + id + " does not exist");
    }
}