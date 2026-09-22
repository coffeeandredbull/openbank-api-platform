package com.openbank.payment.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdAndAccount_OwnerUserId(Long id, Long ownerUserId);

    List<Transaction> findByAccount_OwnerUserIdOrderByIdAsc(Long ownerUserId);
}