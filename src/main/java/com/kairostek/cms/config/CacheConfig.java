package com.kairostek.cms.config;

import com.kairostek.cms.dto.ContentResponse;
import com.kairostek.cms.service.ContentCache;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    @Bean
    public ContentCache contentCache(RedisTemplate<String, ContentResponse> redisTemplate,
                                     CacheProperties cacheProperties) {
        return new ContentCache(redisTemplate, cacheProperties.ttlMinutes(), cacheProperties.jitterPercent());
    }
}
