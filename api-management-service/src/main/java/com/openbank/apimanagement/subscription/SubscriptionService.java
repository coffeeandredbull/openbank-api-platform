package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.api.Api;
import com.openbank.apimanagement.api.ApiVersion;
import com.openbank.apimanagement.api.ApiVersionRepository;
import com.openbank.apimanagement.api.ApiRepository;
import com.openbank.apimanagement.application.Application;
import com.openbank.apimanagement.application.ApplicationRepository;
import com.openbank.apimanagement.exception.ApiVersionNotFoundException;
import com.openbank.apimanagement.exception.ApplicationNotFoundException;
import com.openbank.apimanagement.exception.InvalidSubscriptionStatusTransitionException;
import com.openbank.apimanagement.exception.SubscriptionAlreadyExistsException;
import com.openbank.apimanagement.exception.SubscriptionNotFoundException;
import com.openbank.apimanagement.exception.SubscriptionTierNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final ApplicationRepository applicationRepository;
    private final ApiVersionRepository apiVersionRepository;
    private final ApiRepository apiRepository;
    private final SubscriptionTierService subscriptionTierService;

    public SubscriptionService(
            SubscriptionRepository subscriptionRepository,
            ApplicationRepository applicationRepository,
            ApiVersionRepository apiVersionRepository,
            ApiRepository apiRepository,
            SubscriptionTierService subscriptionTierService) {
        this.subscriptionRepository = subscriptionRepository;
        this.applicationRepository = applicationRepository;
        this.apiVersionRepository = apiVersionRepository;
        this.apiRepository = apiRepository;
        this.subscriptionTierService = subscriptionTierService;
    }

    @Transactional
    public SubscriptionResponse create(Long ownerUserId, CreateSubscriptionRequest request) {
        Application application = applicationRepository
                .findByIdAndOwnerUserId(request.applicationId(), ownerUserId)
                .orElseThrow(() -> new ApplicationNotFoundException(request.applicationId()));
        ApiVersion apiVersion = apiVersionRepository.findById(request.apiVersionId())
                .orElseThrow(() -> new ApiVersionNotFoundException(request.apiVersionId()));
        SubscriptionTier tier = subscriptionTierService.get(request.tierId());
        if (subscriptionRepository.existsByApplicationIdAndApiVersionId(
                request.applicationId(), request.apiVersionId())) {
            throw new SubscriptionAlreadyExistsException(request.applicationId(), request.apiVersionId());
        }
        try {
            Subscription saved = subscriptionRepository.save(new Subscription(application, apiVersion, tier));
            return SubscriptionResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new SubscriptionAlreadyExistsException(request.applicationId(), request.apiVersionId());
        }
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse get(Long subscriptionId, Long ownerUserId) {
        Subscription subscription = subscriptionRepository
                .findByIdAndApplication_OwnerUserId(subscriptionId, ownerUserId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));
        return SubscriptionResponse.from(subscription);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> list(Long ownerUserId) {
        return subscriptionRepository.findByApplication_OwnerUserIdOrderByIdAsc(ownerUserId).stream()
                .map(SubscriptionResponse::from)
                .toList();
    }

    @Transactional
    public SubscriptionResponse changeStatus(Long subscriptionId, UpdateSubscriptionStatusRequest request) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));
        if (!subscription.getStatus().canTransitionTo(request.status())) {
            throw new InvalidSubscriptionStatusTransitionException(subscription.getStatus(), request.status());
        }
        subscription.changeStatus(request.status());
        return SubscriptionResponse.from(subscriptionRepository.save(subscription));
    }

    @Transactional(readOnly = true)
    public SubscriptionPolicy findActivePolicy(Long ownerUserId, String contextPath, String version) {
        ApiVersion apiVersion = findApiVersion(contextPath, version);
        if (apiVersion == null) {
            return null;
        }
        return subscriptionRepository
                .findByApiVersionIdAndApplication_OwnerUserIdAndStatus(
                        apiVersion.getId(), ownerUserId, SubscriptionStatus.ACTIVE)
                .filter(subscription -> subscription.getStatus() == SubscriptionStatus.ACTIVE)
                .map(SubscriptionPolicy::from)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public SubscriptionPolicy findActivePolicyByApplication(Long applicationId, String contextPath, String version) {
        ApiVersion apiVersion = findApiVersion(contextPath, version);
        if (apiVersion == null) {
            return null;
        }
        return subscriptionRepository
                .findByApiVersionIdAndApplicationIdAndStatus(
                        apiVersion.getId(), applicationId, SubscriptionStatus.ACTIVE)
                .filter(subscription -> subscription.getStatus() == SubscriptionStatus.ACTIVE)
                .map(SubscriptionPolicy::from)
                .orElse(null);
    }

    private ApiVersion findApiVersion(String contextPath, String version) {
        Api api = apiRepository.findByContextPath(contextPath).orElse(null);
        if (api == null) {
            return null;
        }
        return apiVersionRepository.findByApiIdAndVersion(api.getId(), version).orElse(null);
    }
}