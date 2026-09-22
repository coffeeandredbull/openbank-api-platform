package com.openbank.payment.transaction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateTransactionRequest(

        @NotNull(message = "accountId is required")
        Long accountId,

        @NotNull(message = "paymentId is required")
        Long paymentId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        @Digits(integer = 17, fraction = 2,
                message = "amount must not exceed 17 digits before the decimal point and 2 digits after")
        BigDecimal amount
) {
}