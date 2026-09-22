package com.openbank.payment.account;

import com.openbank.payment.exception.AccountAlreadyExistsException;
import com.openbank.payment.exception.AccountNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AccountService accountService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    @Test
    void createSetsOwnerFromJwtAndDefaultsCurrencyToLkr() {
        when(accountRepository.existsByOwnerUserId(42L)).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            setField(saved, "id", 1L);
            setField(saved, "createdAt", TIMESTAMP);
            setField(saved, "updatedAt", TIMESTAMP);
            return saved;
        });

        AccountResponse response = accountService.create(42L, new CreateAccountRequest(null));

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account persisted = captor.getValue();
        assertThat(persisted.getOwnerUserId()).isEqualTo(42L);
        assertThat(persisted.getCurrency()).isEqualTo("LKR");
        assertThat(persisted.getCreatedAt()).isEqualTo(persisted.getUpdatedAt());

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.ownerUserId()).isEqualTo(42L);
        assertThat(response.currency()).isEqualTo("LKR");
    }

    @Test
    void createUsesSuppliedValidCurrency() {
        when(accountRepository.existsByOwnerUserId(42L)).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            setField(saved, "id", 1L);
            return saved;
        });

        AccountResponse response = accountService.create(42L, new CreateAccountRequest("USD"));

        assertThat(response.currency()).isEqualTo("USD");
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    void createTrimsBlankCurrencyToDefault() {
        when(accountRepository.existsByOwnerUserId(42L)).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            setField(saved, "id", 1L);
            return saved;
        });

        AccountResponse response = accountService.create(42L, new CreateAccountRequest("  "));

        assertThat(response.currency()).isEqualTo("LKR");
    }

    @Test
    void createRejectsDuplicateAccount() {
        when(accountRepository.existsByOwnerUserId(42L)).thenReturn(true);

        assertThatThrownBy(() -> accountService.create(42L, new CreateAccountRequest(null)))
                .isInstanceOf(AccountAlreadyExistsException.class)
                .hasMessageContaining("42");
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void getReturnsAccountOwnedByCurrentUser() {
        Account stored = account(1L, 42L, "LKR", TIMESTAMP, TIMESTAMP);
        when(accountRepository.findByIdAndOwnerUserId(1L, 42L)).thenReturn(Optional.of(stored));

        AccountResponse response = accountService.get(1L, 42L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.ownerUserId()).isEqualTo(42L);
        assertThat(response.currency()).isEqualTo("LKR");
        assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
        assertThat(response.updatedAt()).isEqualTo(TIMESTAMP);
    }

    @Test
    void getThrowsNotFoundWhenAccountBelongsToAnotherUser() {
        when(accountRepository.findByIdAndOwnerUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.get(1L, 7L))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("1");
    }

    @Test
    void listReturnsOwnAccountWhenItExists() {
        Account stored = account(1L, 42L, "LKR", TIMESTAMP, TIMESTAMP);
        when(accountRepository.findByOwnerUserId(42L)).thenReturn(Optional.of(stored));

        List<AccountResponse> responses = accountService.list(42L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id()).isEqualTo(1L);
        assertThat(responses.get(0).ownerUserId()).isEqualTo(42L);
    }

    @Test
    void listReturnsEmptyListWhenNoAccountExists() {
        when(accountRepository.findByOwnerUserId(42L)).thenReturn(Optional.empty());

        List<AccountResponse> responses = accountService.list(42L);

        assertThat(responses).isEmpty();
    }

    private Account account(Long id, Long ownerUserId, String currency, Instant createdAt, Instant updatedAt) {
        Account account = new Account(ownerUserId, currency);
        setField(account, "id", id);
        setField(account, "createdAt", createdAt);
        setField(account, "updatedAt", updatedAt);
        return account;
    }

    private void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}