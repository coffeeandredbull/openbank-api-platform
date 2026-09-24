package com.openbank.apimanagement.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evicts a specific Redis cache entry after the surrounding database
 * transaction has committed. The annotation-driven {@code CacheEvict} fires as
 * soon as the service method returns, which is before the transaction is
 * committed, so a write that is later rolled back would remove cache entries
 * that are still valid. Registering a post-commit synchronization keeps
 * invalidation in the intended order: commit first, then evict.
 *
 * <p>The cached read models are keyed by database identifiers ({@code apiCatalog}
 * by api id, {@code apiVersion} by (api id, version id), {@code subscriptionTier}
 * by tier id), so the only invalidation ever needed is a per-key eviction for an
 * in-place mutation of an existing row (today: {@code ApiVersionService.changeLifecycle}).
 * Creates introduce fresh ids that were never cacheable, so they evict nothing.
 * Whole-cache clearing is deliberately not provided.
 */
@Component
public class CacheInvalidationService {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationService.class);

    private final CacheManager cacheManager;

    public CacheInvalidationService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void evict(String cacheName, Object key) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            registerAfterCommit(cacheName, key);
        } else {
            evictNow(cacheName, key);
        }
    }

    private void registerAfterCommit(String cacheName, Object key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    evictNow(cacheName, key);
                } catch (RuntimeException ex) {
                    log.warn("Post-commit cache eviction failed for cache '{}' key '{}'; stale data expires via TTL",
                            cacheName, key, ex);
                }
            }
        });
    }

    private void evictNow(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }
}