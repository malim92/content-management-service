package com.kairostek.cms.service;

import com.kairostek.cms.dto.ContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Thin, fault-tolerant cache-aside wrapper over Redis.
 *
 * <p>Every Redis call is guarded: if Redis is unavailable the method degrades
 * gracefully (empty read / no-op write) instead of failing the request, so the
 * application keeps working safely on PostgreSQL alone.
 */
public class ContentCache {

    private static final Logger log = LoggerFactory.getLogger(ContentCache.class);
    private static final String KEY_PREFIX = "cms:content:";

    private final RedisTemplate<String, ContentResponse> redisTemplate;
    private final long ttlMinutes;
    private final int jitterPercent;

    public ContentCache(RedisTemplate<String, ContentResponse> redisTemplate, long ttlMinutes, int jitterPercent) {
        this.redisTemplate = redisTemplate;
        this.ttlMinutes = ttlMinutes;
        this.jitterPercent = jitterPercent;
    }

    private static String key(Long id) {
        return KEY_PREFIX + id;
    }

    public Optional<ContentResponse> get(Long id) {
        try {
            ContentResponse cached = redisTemplate.opsForValue().get(key(id));
            if (cached != null) {
                log.debug("CACHE HIT  for id={} (serving from Redis, no database query)", id);
                return Optional.of(cached);
            }
            log.debug("CACHE MISS for id={} (falling through to PostgreSQL)", id);
            return Optional.empty();
        } catch (RuntimeException ex) {
            logRedisUnavailable("read", ex);
            return Optional.empty();
        }
    }

    public void put(Long id, ContentResponse content) {
        try {
            Duration ttl = ttlWithJitter();
            redisTemplate.opsForValue().set(key(id), content, ttl);
            log.debug("CACHE PUT  id={} ttl={}s", id, ttl.getSeconds());
        } catch (RuntimeException ex) {
            logRedisUnavailable("write", ex);
        }
    }

    public void evict(Long id) {
        try {
            redisTemplate.delete(key(id));
            log.debug("CACHE EVICT id={}", id);
        } catch (RuntimeException ex) {
            logRedisUnavailable("evict", ex);
        }
    }

    /**
     * TTL with random jitter to spread out expiration times and avoid mass
     * simultaneous cache misses.
     */
    private Duration ttlWithJitter() {
        long baseSeconds = Duration.ofMinutes(ttlMinutes).getSeconds();
        long jitter = (long) (baseSeconds * (jitterPercent / 100.0) * ThreadLocalRandom.current().nextDouble());
        return Duration.ofSeconds(baseSeconds + jitter);
    }

    private void logRedisUnavailable(String operation, RuntimeException ex) {
        log.warn("Redis unavailable during cache {} - degrading to database-only mode: {}",
                operation, ex.getMessage());
    }
}
