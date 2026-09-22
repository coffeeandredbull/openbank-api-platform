package com.openbank.payment.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByOwnerUserId(Long ownerUserId);

    Optional<Account> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    boolean existsByOwnerUserId(Long ownerUserId);
}