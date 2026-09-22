package com.openbank.apimanagement.application;

import com.openbank.apimanagement.exception.ApplicationNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;

    public ApplicationService(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    @Transactional
    public ApplicationResponse create(Long ownerUserId, CreateApplicationRequest request) {
        Application saved = applicationRepository.save(
                new Application(request.name(), request.description(), ownerUserId));
        return ApplicationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ApplicationResponse get(Long applicationId, Long ownerUserId) {
        Application application = applicationRepository.findByIdAndOwnerUserId(applicationId, ownerUserId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        return ApplicationResponse.from(application);
    }

    @Transactional(readOnly = true)
    public List<ApplicationResponse> list(Long ownerUserId) {
        return applicationRepository.findByOwnerUserIdOrderByIdAsc(ownerUserId).stream()
                .map(ApplicationResponse::from)
                .toList();
    }

    @Transactional
    public ApplicationResponse update(Long applicationId, Long ownerUserId, UpdateApplicationRequest request) {
        Application application = applicationRepository.findByIdAndOwnerUserId(applicationId, ownerUserId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        if (request.name() == null && request.description() == null) {
            return ApplicationResponse.from(application);
        }
        application.update(request.name(), request.description());
        return ApplicationResponse.from(applicationRepository.save(application));
    }
}