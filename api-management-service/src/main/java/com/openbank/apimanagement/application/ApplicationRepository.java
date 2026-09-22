package com.openbank.apimanagement.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    Optional<Application> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    List<Application> findByOwnerUserIdOrderByIdAsc(Long ownerUserId);
}