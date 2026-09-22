package com.openbank.apimanagement.api;

import com.openbank.apimanagement.exception.ApiNotFoundException;
import com.openbank.apimanagement.exception.ApiVersionAlreadyExistsException;
import com.openbank.apimanagement.exception.ApiVersionNotFoundException;
import com.openbank.apimanagement.exception.InvalidLifecycleTransitionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApiVersionService {

    private final ApiVersionRepository apiVersionRepository;
    private final ApiRepository apiRepository;

    public ApiVersionService(ApiVersionRepository apiVersionRepository, ApiRepository apiRepository) {
        this.apiVersionRepository = apiVersionRepository;
        this.apiRepository = apiRepository;
    }

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
        return ApiVersionResponse.from(apiVersionRepository.save(apiVersion));
    }

    private void requireApiExists(Long apiId) {
        if (!apiRepository.existsById(apiId)) {
            throw new ApiNotFoundException(apiId);
        }
    }
}