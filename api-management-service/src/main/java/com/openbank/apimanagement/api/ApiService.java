package com.openbank.apimanagement.api;

import com.openbank.apimanagement.cache.CacheInvalidationService;
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
    private final CacheInvalidationService cacheInvalidationService;

    public ApiService(ApiRepository apiRepository, CacheInvalidationService cacheInvalidationService) {
        this.apiRepository = apiRepository;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    @Transactional
    public ApiResponse create(CreateApiRequest request) {
        if (apiRepository.existsByContextPath(request.contextPath())) {
            throw new ContextPathAlreadyExistsException(request.contextPath());
        }
        try {
            Api saved = apiRepository.save(new Api(request.name(), request.description(), request.contextPath()));
            cacheInvalidationService.evictAll("apiCatalog");
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