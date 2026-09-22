package com.openbank.payment.payment;

import com.openbank.payment.account.Account;
import com.openbank.payment.account.AccountRepository;
import com.openbank.payment.exception.AccountNotFoundException;
import com.openbank.payment.exception.PaymentNotFoundException;
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
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private PaymentService paymentService;

    private static final Instant TIMESTAMP = Instant.parse("2026-09-21T10:00:00Z");

    @Test
    void createDerivesCurrencyFromAccountAndStartsPending() {
        Account account = account(1L, 42L, "LKR", TIMESTAMP);
        when(accountRepository.findByIdAndOwnerUserId(1L, 42L)).thenReturn(Optional.of(account));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment saved = invocation.getArgument(0);
            setField(saved, "id", 10L);
            setField(saved, "createdAt", TIMESTAMP);
            setField(saved, "updatedAt", TIMESTAMP);
            return saved;
        });

        PaymentResponse response = paymentService.create(
                42L, new CreatePaymentRequest(1L, new BigDecimal("25.50"), "Lunch"));

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment persisted = captor.getValue();
        assertThat(persisted.getAccount().getId()).isEqualTo(1L);
        assertThat(persisted.getAmount()).isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat(persisted.getCurrency()).isEqualTo("LKR");
        assertThat(persisted.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(persisted.getCurrency()).isEqualTo(account.getCurrency());
        assertThat(persisted.getCreatedAt()).isEqualTo(persisted.getUpdatedAt());

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat(response.currency()).isEqualTo("LKR");
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void createStoresSuppliedDescription() {
        Account account = account(1L, 42L, "EUR", TIMESTAMP);
        when(accountRepository.findByIdAndOwnerUserId(1L, 42L)).thenReturn(Optional.of(account));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        paymentService.create(42L, new CreatePaymentRequest(1L, new BigDecimal("10.00"), "Groceries"));

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo("Groceries");
        assertThat(captor.getValue().getCurrency()).isEqualTo("EUR");
    }

    @Test
    void createRejectsMissingAccount() {
        when(accountRepository.findByIdAndOwnerUserId(999L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.create(42L, new CreatePaymentRequest(999L, BigDecimal.ONE, null)))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("999");
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void createRejectsAccountOwnedByAnotherUser() {
        when(accountRepository.findByIdAndOwnerUserId(1L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.create(42L, new CreatePaymentRequest(1L, BigDecimal.ONE, null)))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("1");
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void getReturnsPaymentOwnedByCurrentUser() {
        Payment stored = payment(10L, 1L, 42L, TIMESTAMP);
        when(paymentRepository.findByIdAndAccount_OwnerUserId(10L, 42L)).thenReturn(Optional.of(stored));

        PaymentResponse response = paymentService.get(10L, 42L);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat(response.currency()).isEqualTo("LKR");
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.createdAt()).isEqualTo(TIMESTAMP);
        assertThat(response.updatedAt()).isEqualTo(TIMESTAMP);
    }

    @Test
    void getThrowsNotFoundWhenPaymentBelongsToAnotherUsersAccount() {
        when(paymentRepository.findByIdAndAccount_OwnerUserId(10L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.get(10L, 7L))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining("10");
    }

    @Test
    void listReturnsOnlyPaymentsForAccountsOwnedByCurrentUserOrderedByIdAsc() {
        Payment first = payment(1L, 1L, 42L, TIMESTAMP);
        Payment second = payment(2L, 1L, 42L, TIMESTAMP);
        when(paymentRepository.findByAccount_OwnerUserIdOrderByIdAsc(42L)).thenReturn(List.of(first, second));

        List<PaymentResponse> responses = paymentService.list(42L);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo(1L);
        assertThat(responses.get(1).id()).isEqualTo(2L);
        verify(paymentRepository).findByAccount_OwnerUserIdOrderByIdAsc(42L);
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