package com.openbank.apimanagement.api;

import com.openbank.apimanagement.exception.ApiNotFoundException;
import com.openbank.apimanagement.exception.ContextPathAlreadyExistsException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApiService {

    private final ApiRepository apiRepository;

    public ApiService(ApiRepository apiRepository) {
        this.apiRepository = apiRepository;
    }

    /**
     * Creates an API. The {@code apiCatalog} cache is keyed by the API's
     * database id and a create only introduces a brand-new id that was never
     * resolvable (and therefore never cached), so no existing cache entry can
     * become stale and no eviction is needed - the new id is naturally a cache
     * miss on its first read. Only an in-place mutation of an existing API (of
     * which none exists today) would need a targeted eviction.
     */
    @Transactional
    public ApiResponse create(CreateApiRequest request) {
        if (apiRepository.existsByContextPath(request.contextPath())) {
            throw new ContextPathAlreadyExistsException(request.contextPath());
        }
        try {
            Api saved = apiRepository.save(new Api(request.name(), request.description(), request.contextPath()));
            return ApiResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new ContextPathAlreadyExistsException(request.contextPath());
        }
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "apiCatalog", key = "#id")
    public ApiResponse get(Long id) {
        Api api = apiRepository.findById(id)
                .orElseThrow(() -> new ApiNotFoundException(id));
        return ApiResponse.from(api);
    }

    @Transactional(readOnly = true)
    public List<ApiResponse> list() {
        return apiRepository.findAllByOrderByIdAsc().stream()
                .map(ApiResponse::from)
                .toList();
    }
}