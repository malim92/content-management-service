package com.kairostek.cms.dto;

import com.kairostek.cms.domain.ContentStatus;

/**
 * Immutable search criteria for GET /api/v1/cms/contents.
 *
 * @param language  exact language match, null = any
 * @param status    exact status match, null = any
 * @param keyPrefix prefix match on the content key, null = any
 * @param page      zero-based page index
 * @param size      page size (capped by {@link #MAX_SIZE})
 */
public record ContentSearchCriteria(
        String language,
        ContentStatus status,
        String keyPrefix,
        int page,
        int size
) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static ContentSearchCriteria of(String language, ContentStatus status, String keyPrefix, Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new ContentSearchCriteria(language, status, keyPrefix, p, s);
    }
}
