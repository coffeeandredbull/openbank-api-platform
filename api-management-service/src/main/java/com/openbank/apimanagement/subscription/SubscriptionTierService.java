package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.cache.CacheInvalidationService;
import com.openbank.apimanagement.exception.SubscriptionTierAlreadyExistsException;
import com.openbank.apimanagement.exception.SubscriptionTierNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionTierService {

    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_DESCRIPTION_LENGTH = 500;

    private static final String NO_LEADING_OR_TRAILING_WHITESPACE = "\\S(?:.*\\S)?";

    private final SubscriptionTierRepository subscriptionTierRepository;
    private final CacheInvalidationService cacheInvalidationService;

    public SubscriptionTierService(
            SubscriptionTierRepository subscriptionTierRepository,
            CacheInvalidationService cacheInvalidationService) {
        this.subscriptionTierRepository = subscriptionTierRepository;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    /**
     * Creates a subscription tier. The {@code subscriptionTier} cache is keyed
     * by the tier's database id and a create only introduces a fresh id that
     * was never resolvable (therefore never cached), so no existing cache entry
     * can become stale and no eviction is needed.
     */
    @Transactional
    public SubscriptionTier create(String name, String description, int requestsPerWindow, int windowSeconds) {
        validate(name, description, requestsPerWindow, windowSeconds);
        if (subscriptionTierRepository.existsByName(name)) {
            throw new SubscriptionTierAlreadyExistsException(name);
        }
        try {
            return subscriptionTierRepository.save(
                    new SubscriptionTier(name, description, requestsPerWindow, windowSeconds));
        } catch (DataIntegrityViolationException ex) {
            throw new SubscriptionTierAlreadyExistsException(name);
        }
    }

    @Transactional
    public SubscriptionTierResponse update(Long tierId, UpdateSubscriptionTierRequest request) {
        requireUpdateField(request);
        SubscriptionTier tier = subscriptionTierRepository.findById(tierId)
                .orElseThrow(() -> new SubscriptionTierNotFoundException(tierId));
        String updatedName = request.name() != null ? request.name() : tier.getName();
        String updatedDescription = request.description() != null
                ? request.description() : tier.getDescription();
        int updatedRequestsPerWindow = request.requestsPerWindow() != null
                ? request.requestsPerWindow() : tier.getRequestsPerWindow();
        int updatedWindowSeconds = request.windowSeconds() != null
                ? request.windowSeconds() : tier.getWindowSeconds();
        validate(updatedName, updatedDescription, updatedRequestsPerWindow, updatedWindowSeconds);
        if (request.name() != null
                && subscriptionTierRepository.existsByNameAndIdNot(request.name(), tierId)) {
            throw new SubscriptionTierAlreadyExistsException(request.name());
        }

        tier.update(request.name(), request.description(), request.requestsPerWindow(), request.windowSeconds());
        try {
            SubscriptionTier saved = subscriptionTierRepository.saveAndFlush(tier);
            cacheInvalidationService.evict("subscriptionTier", tierId);
            return SubscriptionTierResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new SubscriptionTierAlreadyExistsException(updatedName);
        }
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "subscriptionTier", key = "#id")
    public SubscriptionTier get(Long id) {
        return subscriptionTierRepository.findById(id)
                .orElseThrow(() -> new SubscriptionTierNotFoundException(id));
    }

    private void requireUpdateField(UpdateSubscriptionTierRequest request) {
        if (request == null
                || (request.name() == null
                && request.description() == null
                && request.requestsPerWindow() == null
                && request.windowSeconds() == null)) {
            throw new IllegalArgumentException("at least one field must be supplied");
        }
    }

    private void validate(String name, String description, int requestsPerWindow, int windowSeconds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (!name.matches(NO_LEADING_OR_TRAILING_WHITESPACE)) {
            throw new IllegalArgumentException("name must not contain leading or trailing whitespace");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must not exceed " + MAX_NAME_LENGTH + " characters");
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description must not exceed " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        if (requestsPerWindow <= 0) {
            throw new IllegalArgumentException("requestsPerWindow must be greater than 0");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be greater than 0");
        }
    }
}