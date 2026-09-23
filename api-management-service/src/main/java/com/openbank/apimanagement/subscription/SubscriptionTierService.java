package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.exception.SubscriptionTierAlreadyExistsException;
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

    @Transactional
    public SubscriptionTier create(String name, String description) {
        validate(name, description);
        if (subscriptionTierRepository.existsByName(name)) {
            throw new SubscriptionTierAlreadyExistsException(name);
        }
        try {
            return subscriptionTierRepository.save(new SubscriptionTier(name, description));
        } catch (DataIntegrityViolationException ex) {
            throw new SubscriptionTierAlreadyExistsException(name);
        }
    }

    private void validate(String name, String description) {
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
    }
}