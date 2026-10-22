package com.kairostek.cms.repository;

import com.kairostek.cms.domain.Content;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link Content}.
 *
 * <p>{@link JpaSpecificationExecutor} backs the filtered, paginated search API
 * with a single, index-friendly query (no in-memory filtering, no N+1).
 */
@Repository
public interface ContentRepository extends JpaRepository<Content, Long>, JpaSpecificationExecutor<Content> {

    Optional<Content> findByKeyAndLanguage(String key, String language);
}
