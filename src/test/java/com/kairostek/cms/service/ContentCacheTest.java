package com.kairostek.cms.service;

import com.kairostek.cms.domain.Content;
import com.kairostek.cms.domain.ContentStatus;
import com.kairostek.cms.dto.ContentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the fault-tolerant behaviour of the cache layer: normal
 * get/put/evict plus graceful degradation when Redis is unavailable.
 */
@ExtendWith(MockitoExtension.class)
class ContentCacheTest {

    @Mock
    private RedisTemplate<String, ContentResponse> redisTemplate;

    @Mock
    private ValueOperations<String, ContentResponse> valueOperations;

    private ContentCache cache;
    private ContentResponse sample;

    @BeforeEach
    void setUp() {
        cache = new ContentCache(redisTemplate, 10, 20);
        Content entity = new Content("homepage.banner", "Welcome", "Welcome to our website", "en", ContentStatus.PUBLISHED);
        TestEntityUtil.setId(entity, 1L);
        sample = ContentResponse.from(entity);
    }

    @Test
    @DisplayName("get: returns the cached value when present")
    void getHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cms:content:1")).thenReturn(sample);

        Optional<ContentResponse> result = cache.get(1L);

        assertThat(result).contains(sample);
    }

    @Test
    @DisplayName("get: returns empty when the key is absent")
    void getMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cms:content:1")).thenReturn(null);

        assertThat(cache.get(1L)).isEmpty();
    }

    @Test
    @DisplayName("get: Redis failure degrades to a cache miss instead of throwing")
    void getRedisDown() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        assertThat(cache.get(1L)).isEmpty();
    }

    @Test
    @DisplayName("put: stores the value with a TTL")
    void put() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cache.put(1L, sample);

        verify(valueOperations).set(eq("cms:content:1"), eq(sample), any(Duration.class));
    }

    @Test
    @DisplayName("put: Redis failure is swallowed so the request still succeeds")
    void putRedisDown() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        cache.put(1L, sample); // must not throw
    }

    @Test
    @DisplayName("evict: deletes the key")
    void evict() {
        cache.evict(1L);

        verify(redisTemplate).delete("cms:content:1");
    }

    @Test
    @DisplayName("evict: Redis failure is swallowed so the request still succeeds")
    void evictRedisDown() {
        doThrow(new RedisConnectionFailureException("Connection refused"))
                .when(redisTemplate).delete(anyString());

        cache.evict(1L); // must not throw
    }
}
