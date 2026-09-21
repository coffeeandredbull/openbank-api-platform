package com.openbank.apimanagement.api;

import com.openbank.apimanagement.exception.ApiNotFoundException;
import com.openbank.apimanagement.exception.ContextPathAlreadyExistsException;
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