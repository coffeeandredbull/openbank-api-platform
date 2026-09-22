package com.openbank.payment.account;

import java.time.Instant;

public record AccountResponse(
        Long id,
        Long ownerUserId,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerUserId(),
                account.getCurrency(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}