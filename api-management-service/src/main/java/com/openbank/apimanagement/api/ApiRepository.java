package com.openbank.apimanagement.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiRepository extends JpaRepository<Api, Long> {

    boolean existsByContextPath(String contextPath);

    Optional<Api> findByContextPath(String contextPath);

    List<Api> findAllByOrderByIdAsc();
}