package com.openbank.apimanagement.api;

import com.openbank.apimanagement.cache.CacheInvalidationService;
import com.openbank.apimanagement.exception.ApiNotFoundException;
import com.openbank.apimanagement.exception.ApiVersionAlreadyExistsException;
import com.openbank.apimanagement.exception.ApiVersionNotFoundException;
import com.openbank.apimanagement.exception.InvalidLifecycleTransitionException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApiVersionService {

    private final ApiVersionRepository apiVersionRepository;
    private final ApiRepository apiRepository;
    private final CacheInvalidationService cacheInvalidationService;

    public ApiVersionService(
            ApiVersionRepository apiVersionRepository,
            ApiRepository apiRepository,
            CacheInvalidationService cacheInvalidationService) {
        this.apiVersionRepository = apiVersionRepository;
        this.apiRepository = apiRepository;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    /**
     * Creates an API version. The {@code apiVersion} cache is keyed by
     * {@code (apiId, versionId)} and a create only introduces a new version id
     * that was never resolvable (therefore never cached); existing cached
     * versions of the same API are untouched by a create, so no eviction is
     * needed - the new version id is a cache miss on its first read.
     */
    @Transactional
    public ApiVersionResponse create(Long apiId, CreateApiVersionRequest request) {
        Api api = apiRepository.findById(apiId)
                .orElseThrow(() -> new ApiNotFoundException(apiId));
        if (apiVersionRepository.existsByApiIdAndVersion(apiId, request.version())) {
            throw new ApiVersionAlreadyExistsException(apiId, request.version());
        }
        try {
            ApiVersion saved = apiVersionRepository.save(new ApiVersion(api, request.version()));
            return ApiVersionResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new ApiVersionAlreadyExistsException(apiId, request.version());
        }
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "apiVersion", key = "#apiId + '::' + #versionId")
    public ApiVersionResponse get(Long apiId, Long versionId) {
        requireApiExists(apiId);
        ApiVersion apiVersion = apiVersionRepository.findByApiIdAndId(apiId, versionId)
                .orElseThrow(() -> new ApiVersionNotFoundException(apiId, versionId));
        return ApiVersionResponse.from(apiVersion);
    }

    @Transactional(readOnly = true)
    public List<ApiVersionResponse> list(Long apiId) {
        requireApiExists(apiId);
        return apiVersionRepository.findByApiIdOrderByIdAsc(apiId).stream()
                .map(ApiVersionResponse::from)
                .toList();
    }

    @Transactional
    public ApiVersionResponse changeLifecycle(Long apiId, Long versionId, UpdateApiVersionLifecycleRequest request) {
        requireApiExists(apiId);
        ApiVersion apiVersion = apiVersionRepository.findByApiIdAndId(apiId, versionId)
                .orElseThrow(() -> new ApiVersionNotFoundException(apiId, versionId));
        if (!apiVersion.getLifecycle().canTransitionTo(request.lifecycle())) {
            throw new InvalidLifecycleTransitionException(apiVersion.getLifecycle(), request.lifecycle());
        }
        apiVersion.changeLifecycle(request.lifecycle());
        ApiVersionResponse response = ApiVersionResponse.from(apiVersionRepository.save(apiVersion));
        cacheInvalidationService.evict("apiVersion", apiId + "::" + versionId);
        return response;
    }

    private void requireApiExists(Long apiId) {
        if (!apiRepository.existsById(apiId)) {
            throw new ApiNotFoundException(apiId);
        }
    }
}