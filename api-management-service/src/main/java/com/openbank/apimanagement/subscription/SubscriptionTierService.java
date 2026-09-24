package com.openbank.apimanagement.subscription;

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

    public SubscriptionTierService(SubscriptionTierRepository subscriptionTierRepository) {
        this.subscriptionTierRepository = subscriptionTierRepository;
    }

    /**
     * Creates a subscription tier. The {@code subscriptionTier} cache is keyed
     * by the tier's database id and a create only introduces a fresh id that
     * was never resolvable (therefore never cached), so no existing cache entry
     * can become stale and no eviction is needed. Tiers have no update/delete
     * mutation today, so the cache is only invalidated if such a mutation is
     * ever introduced.
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

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "subscriptionTier", key = "#id")
    public SubscriptionTier get(Long id) {
        return subscriptionTierRepository.findById(id)
                .orElseThrow(() -> new SubscriptionTierNotFoundException(id));
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