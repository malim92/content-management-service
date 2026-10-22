package com.kairostek.cms.service;

import com.kairostek.cms.domain.Content;
import com.kairostek.cms.dto.ContentCreateRequest;
import com.kairostek.cms.dto.ContentResponse;
import com.kairostek.cms.dto.ContentSearchCriteria;
import com.kairostek.cms.dto.ContentUpdateRequest;
import com.kairostek.cms.exception.ConcurrentUpdateException;
import com.kairostek.cms.exception.ContentNotFoundException;
import com.kairostek.cms.exception.DuplicateContentKeyException;
import com.kairostek.cms.repository.ContentRepository;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for CMS content.
 *
 * <p>Read path (by id) is cache-aside: Redis first, PostgreSQL on a miss,
 * then the result is written back to Redis. Every mutation (create, update,
 * delete) commits to PostgreSQL first and only then invalidates the Redis
 * entry, so the cache never outlives a committed state for long (and never
 * serves data the database no longer has, because the entry is removed
 * inside the same logical operation).
 *
 * <p>Concurrent updates are prevented with optimistic locking
 * ({@code @Version}): a PUT carries the version the client last saw; a stale
 * version is rejected with {@link ConcurrentUpdateException} (HTTP 409),
 * so one administrator can never silently overwrite another's changes.
 */
@Service
public class ContentService {

    private static final Logger log = LoggerFactory.getLogger(ContentService.class);

    private final ContentRepository repository;
    private final ContentCache cache;

    public ContentService(ContentRepository repository, ContentCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    @Transactional
    public ContentResponse create(ContentCreateRequest request) {
        repository.findByKeyAndLanguage(request.key(), request.language()).ifPresent(existing -> {
            throw new DuplicateContentKeyException(request.key(), request.language());
        });

        Content entity = new Content(
                request.key().trim(),
                request.title(),
                request.content(),
                request.language(),
                request.status()
        );
        try {
            Content saved = repository.saveAndFlush(entity);
            cache.put(saved.getId(), ContentResponse.from(saved));
            return ContentResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            // Race-safe fallback: another transaction may have inserted the
            // same (key, language) between our check and the insert.
            throw new DuplicateContentKeyException(request.key(), request.language());
        }
    }

    /**
     * Cache-aside read: Redis first, database on miss, back-fill Redis.
     * Works safely (database-only) when Redis is down.
     */
    @Transactional(readOnly = true)
    public ContentResponse getById(Long id) {
        return cache.get(id).orElseGet(() -> {
            Content entity = repository.findById(id)
                    .orElseThrow(() -> new ContentNotFoundException(id));
            ContentResponse response = ContentResponse.from(entity);
            cache.put(id, response);
            return response;
        });
    }

    @Transactional
    public ContentResponse update(Long id, ContentUpdateRequest request) {
        Content entity = repository.findById(id)
                .orElseThrow(() -> new ContentNotFoundException(id));

        if (entity.getVersion() != request.expectedVersion()) {
            throw new ConcurrentUpdateException(id);
        }

        entity.applyUpdate(request.title(), request.content(), request.status());
        try {
            Content saved = repository.saveAndFlush(entity);
            cache.evict(id);
            return ContentResponse.from(saved);
        } catch (OptimisticLockException | org.springframework.dao.OptimisticLockingFailureException ex) {
            // Lost the race between our read and the write.
            throw new ConcurrentUpdateException(id);
        }
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ContentNotFoundException(id);
        }
        repository.deleteById(id);
        cache.evict(id);
    }

    /**
     * Filtered, paginated search executed as a single SQL query via JPA
     * Specifications. Not cached: search traffic is diverse and pagination
     * makes each page cheap; caching pages would add invalidation complexity
     * with little gain at 10k reads/min.
     */
    @Transactional(readOnly = true)
    public Page<ContentResponse> search(ContentSearchCriteria criteria) {
        Specification<Content> spec = Specification.where(null);
        if (criteria.language() != null && !criteria.language().isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("language"), criteria.language()));
        }
        if (criteria.status() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), criteria.status()));
        }
        if (criteria.keyPrefix() != null && !criteria.keyPrefix().isBlank()) {
            spec = spec.and((root, q, cb) -> cb.like(root.get("key"), criteria.keyPrefix() + "%"));
        }

        Pageable pageable = PageRequest.of(criteria.page(), criteria.size(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return repository.findAll(spec, pageable).map(ContentResponse::from);
    }
}
