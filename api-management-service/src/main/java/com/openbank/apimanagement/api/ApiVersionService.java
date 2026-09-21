package com.openbank.apimanagement.api;

import com.openbank.apimanagement.exception.ApiNotFoundException;
import com.openbank.apimanagement.exception.ApiVersionAlreadyExistsException;
import com.openbank.apimanagement.exception.ApiVersionNotFoundException;
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

    private void requireApiExists(Long apiId) {
        if (!apiRepository.existsById(apiId)) {
            throw new ApiNotFoundException(apiId);
        }
    }
}