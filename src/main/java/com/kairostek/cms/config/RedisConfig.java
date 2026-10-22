package com.kairostek.cms.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kairostek.cms.dto.ContentResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * RedisTemplate configured for the content cache: String keys, values
     * serialized to JSON as {@link ContentResponse}. The serializer is typed
     * to ContentResponse directly so deserialization returns the right type
     * (important because ContentResponse is a final record — generic default
     * typing would round-trip it into a LinkedHashMap and cache hits would
     * never be recognized).
     */
    @Bean
    public RedisTemplate<String, ContentResponse> redisTemplate(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.registerModule(new JavaTimeModule());
        mapper.findAndRegisterModules();

        Jackson2JsonRedisSerializer<ContentResponse> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(mapper, ContentResponse.class);

        RedisTemplate<String, ContentResponse> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
