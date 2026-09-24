package com.openbank.apimanagement.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evicts Redis cache entries after the surrounding database transaction has
 * committed. The annotation-driven {@code CacheEvict} fires as soon as the
 * service method returns, which is before the transaction is committed, so a
 * write that is later rolled back would remove cache entries that are still
 * valid. Registering a post-commit synchronization keeps invalidation in the
 * intended order: commit first, then evict.
 */
@Component
public class CacheInvalidationService {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationService.class);

    private final CacheManager cacheManager;

    public CacheInvalidationService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void evictAll(String cacheName) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            registerAfterCommit(cacheName, null);
        } else {
            clearNow(cacheName);
        }
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
                    if (key == null) {
                        clearNow(cacheName);
                    } else {
                        evictNow(cacheName, key);
                    }
                } catch (RuntimeException ex) {
                    log.warn("Post-commit cache eviction failed for cache '{}'{}; stale data expires via TTL",
                            cacheName, key == null ? "" : " key '" + key + "'", ex);
                }
            }
        });
    }

    private void clearNow(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    private void evictNow(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }
}