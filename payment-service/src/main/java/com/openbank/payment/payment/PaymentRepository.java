package com.openbank.payment.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdAndAccount_OwnerUserId(Long id, Long ownerUserId);

    List<Payment> findByAccount_OwnerUserIdOrderByIdAsc(Long ownerUserId);

    Optional<Payment> findByIdAndAccountId(Long id, Long accountId);
}