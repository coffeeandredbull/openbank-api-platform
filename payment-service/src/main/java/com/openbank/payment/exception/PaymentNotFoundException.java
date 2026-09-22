package com.openbank.payment.exception;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(Long id) {
        super("Payment with id " + id + " does not exist");
    }
}