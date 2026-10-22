package com.kairostek.cms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * CMS content entity.
 *
 * <p>Concurrency: optimistic locking via the {@code version} column
 * ({@link Version}). A unique constraint on (key, language) guarantees that
 * the same logical content key can only exist once per language.
 */
@Entity
@Table(name = "contents", uniqueConstraints = {
        @UniqueConstraint(name = "uk_contents_key_language", columnNames = {"content_key", "language"})
})
public class Content {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_key", nullable = false, length = 255)
    private String key;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false, length = 10)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContentStatus status;

    /**
     * Optimistic lock. Managed by JPA/Hibernate, never set by clients
     * directly. Sent to clients so they can send it back on updates.
     */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Content() {
    }

    public Content(String key, String title, String content, String language, ContentStatus status) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.language = Objects.requireNonNull(language, "language must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void applyUpdate(String title, String content, ContentStatus status) {
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public Long getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getLanguage() {
        return language;
    }

    public ContentStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
