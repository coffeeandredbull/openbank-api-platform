package com.openbank.apimanagement.subscription;

import com.openbank.apimanagement.cache.CacheInvalidationService;
import com.openbank.apimanagement.exception.SubscriptionTierAlreadyExistsException;
import com.openbank.apimanagement.exception.SubscriptionTierInUseException;
import com.openbank.apimanagement.exception.SubscriptionTierNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SubscriptionTierService {

    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_DESCRIPTION_LENGTH = 500;
    static final int DEFAULT_REQUESTS_PER_WINDOW = 100;
    static final int DEFAULT_WINDOW_SECONDS = 60;
    static final int DEFAULT_PRICE_CENTS_PER_MONTH = 0;

    private static final String NO_LEADING_OR_TRAILING_WHITESPACE = "\\S(?:.*\\S)?";

    private final SubscriptionTierRepository subscriptionTierRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final CacheInvalidationService cacheInvalidationService;

    public SubscriptionTierService(
            SubscriptionTierRepository subscriptionTierRepository,
            SubscriptionRepository subscriptionRepository,
            CacheInvalidationService cacheInvalidationService) {
        this.subscriptionTierRepository = subscriptionTierRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    /**
     * Creates a subscription tier from an HTTP request body, applying the
     * documented default rate-limit policy when the optional fields are
     * omitted. The {@code subscriptionTier} cache is keyed by the tier's
     * database id and a create only introduces a fresh id that was never
     * resolvable (therefore never cached), so no existing cache entry can
     * become stale and no eviction is needed.
     */
    @Transactional
    public SubscriptionTierResponse create(CreateSubscriptionTierRequest request) {
        int requestsPerWindow = request.requestsPerWindow() != null
                ? request.requestsPerWindow()
                : DEFAULT_REQUESTS_PER_WINDOW;
        int windowSeconds = request.windowSeconds() != null
                ? request.windowSeconds()
                : DEFAULT_WINDOW_SECONDS;
        int priceCentsPerMonth = request.priceCentsPerMonth() != null
                ? request.priceCentsPerMonth()
                : DEFAULT_PRICE_CENTS_PER_MONTH;
        SubscriptionTier created = create(
                request.name(), request.description(), requestsPerWindow, windowSeconds, priceCentsPerMonth);
        return SubscriptionTierResponse.from(created);
    }

    /**
     * Creates a subscription tier. The {@code subscriptionTier} cache is keyed
     * by the tier's database id and a create only introduces a fresh id that
     * was never resolvable (therefore never cached), so no existing cache entry
     * can become stale and no eviction is needed.
     */
    @Transactional
    public SubscriptionTier create(String name, String description, int requestsPerWindow, int windowSeconds) {
        return create(name, description, requestsPerWindow, windowSeconds, DEFAULT_PRICE_CENTS_PER_MONTH);
    }

    @Transactional
    public SubscriptionTier create(
            String name, String description, int requestsPerWindow, int windowSeconds, int priceCentsPerMonth) {
        validate(name, description, requestsPerWindow, windowSeconds, priceCentsPerMonth);
        if (subscriptionTierRepository.existsByName(name)) {
            throw new SubscriptionTierAlreadyExistsException(name);
        }
        try {
            return subscriptionTierRepository.save(
                    new SubscriptionTier(name, description, requestsPerWindow, windowSeconds, priceCentsPerMonth));
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
        int updatedPriceCentsPerMonth = request.priceCentsPerMonth() != null
                ? request.priceCentsPerMonth() : tier.getPriceCentsPerMonth();
        validate(updatedName, updatedDescription, updatedRequestsPerWindow, updatedWindowSeconds,
                updatedPriceCentsPerMonth);
        if (request.name() != null
                && subscriptionTierRepository.existsByNameAndIdNot(request.name(), tierId)) {
            throw new SubscriptionTierAlreadyExistsException(request.name());
        }

        tier.update(request.name(), request.description(), request.requestsPerWindow(), request.windowSeconds(),
                request.priceCentsPerMonth());
        try {
            SubscriptionTier saved = subscriptionTierRepository.saveAndFlush(tier);
            cacheInvalidationService.evict("subscriptionTier", tierId);
            return SubscriptionTierResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new SubscriptionTierAlreadyExistsException(updatedName);
        }
    }

    @Transactional
    public void delete(Long tierId) {
        SubscriptionTier tier = subscriptionTierRepository.findById(tierId)
                .orElseThrow(() -> new SubscriptionTierNotFoundException(tierId));
        if (subscriptionRepository.existsByTierId(tierId)) {
            throw new SubscriptionTierInUseException(tierId);
        }
        try {
            subscriptionTierRepository.delete(tier);
            subscriptionTierRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new SubscriptionTierInUseException(tierId);
        }
        cacheInvalidationService.evict("subscriptionTier", tierId);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "subscriptionTier", key = "#id")
    public SubscriptionTier get(Long id) {
        return subscriptionTierRepository.findById(id)
                .orElseThrow(() -> new SubscriptionTierNotFoundException(id));
    }

    /**
     * Changes only the lifecycle status of an existing tier. The tier status is
     * a two-state lifecycle ({@code ACTIVE}/{@code INACTIVE}) with no
     * transition restrictions, so setting the current status again is an
     * idempotent no-op: the tier is not saved, its timestamps are untouched,
     * and no cache entry is evicted (nothing can have gone stale). A real
     * change saves and flushes first and then evicts exactly its own
     * {@code subscriptionTier::<tierId>} entry through the post-commit
     * {@link CacheInvalidationService}, so a rejected or rolled-back update
     * evicts nothing.
     */
    @Transactional
    public SubscriptionTierResponse changeStatus(Long tierId, UpdateSubscriptionTierStatusRequest request) {
        SubscriptionTier tier = subscriptionTierRepository.findById(tierId)
                .orElseThrow(() -> new SubscriptionTierNotFoundException(tierId));
        if (tier.getStatus() == request.status()) {
            return SubscriptionTierResponse.from(tier);
        }
        tier.changeStatus(request.status());
        SubscriptionTier saved = subscriptionTierRepository.saveAndFlush(tier);
        cacheInvalidationService.evict("subscriptionTier", tierId);
        return SubscriptionTierResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionTierResponse> list() {
        return subscriptionTierRepository.findAllByOrderByIdAsc().stream()
                .map(SubscriptionTierResponse::from)
                .toList();
    }

    private void requireUpdateField(UpdateSubscriptionTierRequest request) {
        if (request == null
                || (request.name() == null
                && request.description() == null
                && request.requestsPerWindow() == null
                && request.windowSeconds() == null
                && request.priceCentsPerMonth() == null)) {
            throw new IllegalArgumentException("at least one field must be supplied");
        }
    }

    private void validate(
            String name,
            String description,
            int requestsPerWindow,
            int windowSeconds,
            int priceCentsPerMonth) {
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
        if (priceCentsPerMonth < 0) {
            throw new IllegalArgumentException("priceCentsPerMonth must not be negative");
        }
    }
}