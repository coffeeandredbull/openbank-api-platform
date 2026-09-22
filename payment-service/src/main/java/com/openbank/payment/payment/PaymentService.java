package com.openbank.payment.payment;

import com.openbank.payment.account.Account;
import com.openbank.payment.account.AccountRepository;
import com.openbank.payment.exception.AccountNotFoundException;
import com.openbank.payment.exception.PaymentNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;

    public PaymentService(PaymentRepository paymentRepository, AccountRepository accountRepository) {
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public PaymentResponse create(Long ownerUserId, CreatePaymentRequest request) {
        Account account = accountRepository
                .findByIdAndOwnerUserId(request.accountId(), ownerUserId)
                .orElseThrow(() -> new AccountNotFoundException(request.accountId()));
        Payment saved = paymentRepository.save(new Payment(account, request.amount(), request.description()));
        return PaymentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Long paymentId, Long ownerUserId) {
        Payment payment = paymentRepository
                .findByIdAndAccount_OwnerUserId(paymentId, ownerUserId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(Long ownerUserId) {
        return paymentRepository.findByAccount_OwnerUserIdOrderByIdAsc(ownerUserId).stream()
                .map(PaymentResponse::from)
                .toList();
    }
}