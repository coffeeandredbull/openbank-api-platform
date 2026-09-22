package com.openbank.payment.account;

import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(

        @Pattern(
                regexp = "[A-Z]{3}",
                message = "currency must be a valid 3-letter uppercase currency code"
        )
        String currency
) {
}