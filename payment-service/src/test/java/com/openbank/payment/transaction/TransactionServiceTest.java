package com.openbank.payment.transaction;

import com.openbank.payment.account.Account;
import com.openbank.payment.account.AccountRepository;
import com.openbank.payment.exception.AccountNotFoundException;
import com.openbank.payment.exception.PaymentNotFoundException;
import com.openbank.payment.exception.TransactionNotFoundException;
import com.openbank.payment.payment.Payment;
import com.openbank.payment.payment.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private TransactionService transactionService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    @Test
    void createStoresTypePaymentAndDerivesCurrencyFromAccount() {
        Account account = account(1L, 42L, "LKR", TIMESTAMP);
        Payment payment = payment(10L, 1L, 42L, TIMESTAMP);
        when(accountRepository.findByIdAndOwnerUserId(1L, 42L)).thenReturn(Optional.of(account));
        when(paymentRepository.findByIdAndAccountId(10L, 1L)).thenReturn(Optional.of(payment));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            setField(saved, "id", 100L);
            setField(saved, "createdAt", TIMESTAMP);
            return saved;
        });

        TransactionResponse response = transactionService.create(
                42L, new CreateTransactionRequest(1L, 10L, new BigDecimal("25.50")));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction persisted = captor.getValue();
        assertThat(persisted.getAccount().getId()).isEqualTo(1L);
        assertThat(persisted.getPayment().getId()).isEqualTo(10L);
        assertThat(persisted.getType()).isEqualTo(TransactionType.PAYMENT);
        assertThat(persisted.getAmount()).isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat(persisted.getCurrency()).isEqualTo("LKR");
        assertThat(persisted.getCurrency()).isEqualTo(account.getCurrency());
        assertThat(persisted.getCreatedAt()).isNotNull();

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.paymentId()).isEqualTo(10L);
        assertThat(response.type()).isEqualTo(TransactionType.PAYMENT);
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat(response.currency()).isEqualTo("LKR");
        assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
    }

    @Test
    void createRejectsMissingAccount() {
        when(accountRepository.findByIdAndOwnerUserId(999L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.create(42L, new CreateTransactionRequest(999L, 10L, BigDecimal.ONE)))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("999");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void createRejectsAccountOwnedByAnotherUser() {
        when(accountRepository.findByIdAndOwnerUserId(1L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.create(42L, new CreateTransactionRequest(1L, 10L, BigDecimal.ONE)))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("1");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void createRejectsMissingPayment() {
        Account account = account(1L, 42L, "LKR", TIMESTAMP);
        when(accountRepository.findByIdAndOwnerUserId(1L, 42L)).thenReturn(Optional.of(account));
        when(paymentRepository.findByIdAndAccountId(999L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.create(42L, new CreateTransactionRequest(1L, 999L, BigDecimal.ONE)))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining("999");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void createRejectsPaymentThatDoesNotBelongToTheAccount() {
        Account account = account(1L, 42L, "LKR", TIMESTAMP);
        when(accountRepository.findByIdAndOwnerUserId(1L, 42L)).thenReturn(Optional.of(account));
        when(paymentRepository.findByIdAndAccountId(10L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.create(42L, new CreateTransactionRequest(1L, 10L, BigDecimal.ONE)))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining("10");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void getReturnsTransactionOwnedByCurrentUser() {
        Transaction stored = transaction(100L, 1L, 42L, 10L, TIMESTAMP);
        when(transactionRepository.findByIdAndAccount_OwnerUserId(100L, 42L)).thenReturn(Optional.of(stored));

        TransactionResponse response = transactionService.get(100L, 42L);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.paymentId()).isEqualTo(10L);
        assertThat(response.type()).isEqualTo(TransactionType.PAYMENT);
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat(response.currency()).isEqualTo("LKR");
        assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
    }

    @Test
    void getThrowsNotFoundWhenTransactionBelongsToAnotherUsersAccount() {
        when(transactionRepository.findByIdAndAccount_OwnerUserId(100L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.get(100L, 7L))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("100");
    }

    @Test
    void listReturnsOnlyTransactionsForAccountsOwnedByCurrentUserOrderedByIdAsc() {
        Transaction first = transaction(1L, 1L, 42L, 10L, TIMESTAMP);
        Transaction second = transaction(2L, 1L, 42L, 11L, TIMESTAMP);
        when(transactionRepository.findByAccount_OwnerUserIdOrderByIdAsc(42L)).thenReturn(List.of(first, second));

        List<TransactionResponse> responses = transactionService.list(42L);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo(1L);
        assertThat(responses.get(1).id()).isEqualTo(2L);
        verify(transactionRepository).findByAccount_OwnerUserIdOrderByIdAsc(42L);
    }

    private Account account(Long id, Long ownerUserId, String currency, Instant timestamp) {
        Account account = new Account(ownerUserId, currency);
        setField(account, "id", id);
        setField(account, "createdAt", timestamp);
        setField(account, "updatedAt", timestamp);
        return account;
    }

    private Payment payment(Long id, Long accountId, Long ownerUserId, Instant timestamp) {
        Payment payment = new Payment(account(accountId, ownerUserId, "LKR", timestamp), new BigDecimal("25.50"), null);
        setField(payment, "id", id);
        setField(payment, "createdAt", timestamp);
        setField(payment, "updatedAt", timestamp);
        return payment;
    }

    private Transaction transaction(Long id, Long accountId, Long ownerUserId, Long paymentId, Instant timestamp) {
        Transaction transaction = new Transaction(
                account(accountId, ownerUserId, "LKR", timestamp),
                payment(paymentId, accountId, ownerUserId, timestamp),
                new BigDecimal("25.50"));
        setField(transaction, "id", id);
        setField(transaction, "createdAt", timestamp);
        return transaction;
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