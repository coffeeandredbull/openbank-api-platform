package com.openbank.payment.transaction;

import com.openbank.payment.account.Account;
import com.openbank.payment.account.AccountRepository;
import com.openbank.payment.exception.AccountNotFoundException;
import com.openbank.payment.exception.PaymentNotFoundException;
import com.openbank.payment.exception.TransactionNotFoundException;
import com.openbank.payment.payment.Payment;
import com.openbank.payment.payment.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            PaymentRepository paymentRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public TransactionResponse create(Long ownerUserId, CreateTransactionRequest request) {
        Account account = accountRepository
                .findByIdAndOwnerUserId(request.accountId(), ownerUserId)
                .orElseThrow(() -> new AccountNotFoundException(request.accountId()));
        Payment payment = paymentRepository
                .findByIdAndAccountId(request.paymentId(), account.getId())
                .orElseThrow(() -> new PaymentNotFoundException(request.paymentId()));
        Transaction saved = transactionRepository.save(new Transaction(account, payment, request.amount()));
        return TransactionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(Long transactionId, Long ownerUserId) {
        Transaction transaction = transactionRepository
                .findByIdAndAccount_OwnerUserId(transactionId, ownerUserId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        return TransactionResponse.from(transaction);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> list(Long ownerUserId) {
        return transactionRepository.findByAccount_OwnerUserIdOrderByIdAsc(ownerUserId).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}