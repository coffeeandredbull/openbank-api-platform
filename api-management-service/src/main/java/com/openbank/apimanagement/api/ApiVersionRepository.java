package com.openbank.apimanagement.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiVersionRepository extends JpaRepository<ApiVersion, Long> {

    List<ApiVersion> findByApiIdOrderByIdAsc(Long apiId);

    Optional<ApiVersion> findByApiIdAndId(Long apiId, Long versionId);

    boolean existsByApiIdAndVersion(Long apiId, String version);
}