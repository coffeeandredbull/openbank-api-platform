package com.openbank.payment.account;

import com.openbank.payment.exception.AccountAlreadyExistsException;
import com.openbank.payment.exception.AccountNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public AccountResponse create(Long ownerUserId, CreateAccountRequest request) {
        if (accountRepository.existsByOwnerUserId(ownerUserId)) {
            throw new AccountAlreadyExistsException(ownerUserId);
        }
        String currency = request.currency() == null || request.currency().isBlank()
                ? Account.DEFAULT_CURRENCY
                : request.currency();
        Account saved = accountRepository.save(new Account(ownerUserId, currency));
        return AccountResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Long accountId, Long ownerUserId) {
        Account account = accountRepository
                .findByIdAndOwnerUserId(accountId, ownerUserId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(Long ownerUserId) {
        Optional<Account> account = accountRepository.findByOwnerUserId(ownerUserId);
        return account.map(AccountResponse::from)
                .map(List::of)
                .orElseGet(List::of);
    }
}