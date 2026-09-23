package com.openbank.apimanagement.credential;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CredentialRepository extends JpaRepository<Credential, Long> {

    Optional<Credential> findByIdAndApplication_OwnerUserId(Long id, Long ownerUserId);

    List<Credential> findByApplication_OwnerUserIdOrderByIdAsc(Long ownerUserId);

    boolean existsByClientId(String clientId);

    Optional<Credential> findByClientId(String clientId);
}