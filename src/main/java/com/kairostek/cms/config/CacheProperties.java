package com.kairostek.cms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cache configuration.
 *
 * @param ttlMinutes time-to-live for a cached content entry. Chosen at 10
 *                   minutes: content changes are relatively rare compared to
 *                   reads, and every write explicitly invalidates the entry,
 *                   so staleness is bounded by the invalidation path rather
 *                   than by TTL. TTL exists only as a safety net.
 * @param jitterPercent random jitter added to each TTL to avoid a thundering
 *                      herd of cache misses when many entries expire at once.
 */
@ConfigurationProperties(prefix = "cms.cache")
public record CacheProperties(long ttlMinutes, int jitterPercent) {

    public CacheProperties {
        if (ttlMinutes <= 0) {
            ttlMinutes = 10;
        }
        if (jitterPercent < 0 || jitterPercent > 100) {
            jitterPercent = 20;
        }
    }
}
