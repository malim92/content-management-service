package com.kairostek.cms.service;

import com.kairostek.cms.domain.Content;
import com.kairostek.cms.domain.ContentStatus;
import com.kairostek.cms.dto.ContentCreateRequest;
import com.kairostek.cms.dto.ContentResponse;
import com.kairostek.cms.dto.ContentSearchCriteria;
import com.kairostek.cms.dto.ContentUpdateRequest;
import com.kairostek.cms.exception.ConcurrentUpdateException;
import com.kairostek.cms.exception.ContentNotFoundException;
import com.kairostek.cms.exception.DuplicateContentKeyException;
import com.kairostek.cms.repository.ContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContentServiceTest {

    @Mock
    private ContentRepository repository;

    @Mock
    private ContentCache cache;

    private ContentService service;

    private Content savedContent;

    @BeforeEach
    void setUp() {
        service = new ContentService(repository, cache);
        savedContent = new Content("homepage.banner", "Welcome", "Welcome to our website", "en", ContentStatus.PUBLISHED);
        TestEntityUtil.setId(savedContent, 1L);
    }

    @Test
    @DisplayName("create: persists content and warms the cache")
    void createSuccess() {
        ContentCreateRequest request = new ContentCreateRequest(
                "homepage.banner", "Welcome", "Welcome to our website", "en", ContentStatus.PUBLISHED);
        when(repository.findByKeyAndLanguage("homepage.banner", "en")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Content.class))).thenReturn(savedContent);

        ContentResponse response = service.create(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.key()).isEqualTo("homepage.banner");
        assertThat(response.status()).isEqualTo("PUBLISHED");
        verify(cache).put(eq(1L), any(ContentResponse.class));
    }

    @Test
    @DisplayName("create: rejects a duplicate (key, language) with 409 semantics")
    void createDuplicateKey() {
        ContentCreateRequest request = new ContentCreateRequest(
                "homepage.banner", "Welcome", "x", "en", ContentStatus.DRAFT);
        when(repository.findByKeyAndLanguage("homepage.banner", "en")).thenReturn(Optional.of(savedContent));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateContentKeyException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create: races on the unique constraint are still mapped to duplicate-key")
    void createDuplicateKeyRace() {
        ContentCreateRequest request = new ContentCreateRequest(
                "homepage.banner", "Welcome", "x", "en", ContentStatus.DRAFT);
        when(repository.findByKeyAndLanguage("homepage.banner", "en")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Content.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateContentKeyException.class);
    }

    @Test
    @DisplayName("getById: cache hit returns without touching the database")
    void getCacheHit() {
        ContentResponse cached = ContentResponse.from(savedContent);
        when(cache.get(1L)).thenReturn(Optional.of(cached));

        ContentResponse response = service.getById(1L);

        assertThat(response.id()).isEqualTo(1L);
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("getById: cache miss falls through to the database and back-fills Redis")
    void getCacheMiss() {
        when(cache.get(1L)).thenReturn(Optional.empty());
        when(repository.findById(1L)).thenReturn(Optional.of(savedContent));

        ContentResponse response = service.getById(1L);

        assertThat(response.key()).isEqualTo("homepage.banner");
        verify(cache).put(eq(1L), any(ContentResponse.class));
    }

    @Test
    @DisplayName("getById: missing content throws ContentNotFoundException")
    void getNotFound() {
        when(cache.get(99L)).thenReturn(Optional.empty());
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(ContentNotFoundException.class);
        verify(cache, never()).put(any(), any());
    }

    @Test
    @DisplayName("update: applies changes and invalidates the cache entry")
    void updateSuccess() {
        ContentUpdateRequest request = new ContentUpdateRequest("New title", "New body", ContentStatus.ARCHIVED, 0L);
        when(repository.findById(1L)).thenReturn(Optional.of(savedContent));
        when(repository.saveAndFlush(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

        ContentResponse response = service.update(1L, request);

        assertThat(response.title()).isEqualTo("New title");
        assertThat(response.status()).isEqualTo("ARCHIVED");
        verify(cache).evict(1L);
    }

    @Test
    @DisplayName("update: stale expectedVersion is rejected so admins cannot overwrite each other")
    void updateConcurrentConflict() {
        ContentUpdateRequest request = new ContentUpdateRequest("New title", "New body", ContentStatus.DRAFT, 5L);
        when(repository.findById(1L)).thenReturn(Optional.of(savedContent));

        assertThatThrownBy(() -> service.update(1L, request))
                .isInstanceOf(ConcurrentUpdateException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(cache, never()).evict(any());
    }

    @Test
    @DisplayName("update: missing content throws ContentNotFoundException")
    void updateNotFound() {
        ContentUpdateRequest request = new ContentUpdateRequest("t", "c", ContentStatus.DRAFT, 0L);
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, request))
                .isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    @DisplayName("delete: removes the row and evicts the cache entry")
    void deleteSuccess() {
        when(repository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(repository).deleteById(1L);
        verify(cache).evict(1L);
    }

    @Test
    @DisplayName("delete: missing content throws ContentNotFoundException")
    void deleteNotFound() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ContentNotFoundException.class);
        verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("search: applies filters and returns a page")
    void search() {
        ContentSearchCriteria criteria = ContentSearchCriteria.of("en", ContentStatus.PUBLISHED, "home", 0, 20);
        Page<Content> page = new PageImpl<>(List.of(savedContent));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Specification<Content>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        when(repository.findAll(specCaptor.capture(), any(Pageable.class))).thenReturn(page);

        Page<ContentResponse> result = service.search(criteria);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).language()).isEqualTo("en");
        assertThat(specCaptor.getValue()).isNotNull();
    }
}
