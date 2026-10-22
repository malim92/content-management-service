package com.kairostek.cms.dto;

import com.kairostek.cms.domain.Content;
import java.time.Instant;

/**
 * Public representation of a content item.
 */
public record ContentResponse(
        Long id,
        String key,
        String title,
        String content,
        String language,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static ContentResponse from(Content entity) {
        return new ContentResponse(
                entity.getId(),
                entity.getKey(),
                entity.getTitle(),
                entity.getContent(),
                entity.getLanguage(),
                entity.getStatus().name(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
