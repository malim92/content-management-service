package com.kairostek.cms.web;

import com.kairostek.cms.domain.ContentStatus;
import com.kairostek.cms.dto.ContentResponse;
import com.kairostek.cms.dto.ContentSearchCriteria;
import com.kairostek.cms.exception.ConcurrentUpdateException;
import com.kairostek.cms.exception.ContentNotFoundException;
import com.kairostek.cms.exception.DuplicateContentKeyException;
import com.kairostek.cms.service.ContentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContentController.class)
class ContentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ContentService contentService;

    private static final String BASE = "/api/v1/cms/contents";

    private static ContentResponse sampleResponse() {
        return new ContentResponse(1L, "homepage.banner", "Welcome", "Welcome to our website",
                "en", "PUBLISHED", 0, Instant.parse("2026-10-21T10:00:00Z"), Instant.parse("2026-10-21T10:00:00Z"));
    }

    @Test
    @DisplayName("POST creates content and returns 201")
    void create() throws Exception {
        when(contentService.create(any())).thenReturn(sampleResponse());

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "homepage.banner",
                                  "title": "Welcome",
                                  "content": "Welcome to our website",
                                  "language": "en",
                                  "status": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.key").value("homepage.banner"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    @DisplayName("POST with an unknown status returns 400 with a consistent error body")
    void createInvalidStatus() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "homepage.banner",
                                  "title": "Welcome",
                                  "content": "Welcome",
                                  "language": "en",
                                  "status": "UNKNOWN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("POST with a blank title returns 400 with field details")
    void createInvalidBody() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "homepage.banner",
                                  "title": "",
                                  "content": "Welcome",
                                  "language": "en",
                                  "status": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details[0].field").value("title"));
    }

    @Test
    @DisplayName("POST with a duplicate key returns 409")
    void createDuplicate() throws Exception {
        when(contentService.create(any()))
                .thenThrow(new DuplicateContentKeyException("homepage.banner", "en"));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "homepage.banner",
                                  "title": "Welcome",
                                  "content": "Welcome",
                                  "language": "en",
                                  "status": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CONTENT_KEY"));
    }

    @Test
    @DisplayName("GET by id returns the content")
    void getById() throws Exception {
        when(contentService.getById(1L)).thenReturn(sampleResponse());

        mockMvc.perform(get(BASE + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("GET by missing id returns 404")
    void getNotFound() throws Exception {
        when(contentService.getById(99L)).thenThrow(new ContentNotFoundException(99L));

        mockMvc.perform(get(BASE + "/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("PUT updates content")
    void update() throws Exception {
        ContentResponse updated = new ContentResponse(1L, "homepage.banner", "New title", "New body",
                "en", "ARCHIVED", 1, Instant.parse("2026-10-21T10:00:00Z"), Instant.parse("2026-10-21T11:00:00Z"));
        when(contentService.update(eq(1L), any())).thenReturn(updated);

        mockMvc.perform(put(BASE + "/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "New title",
                                  "content": "New body",
                                  "status": "ARCHIVED",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("New title"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("PUT with a stale version returns 409 CONCURRENT_UPDATE")
    void updateConflict() throws Exception {
        when(contentService.update(eq(1L), any())).thenThrow(new ConcurrentUpdateException(1L));

        mockMvc.perform(put(BASE + "/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "New title",
                                  "content": "New body",
                                  "status": "DRAFT",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));
    }

    @Test
    @DisplayName("DELETE returns 204")
    void deleteContent() throws Exception {
        mockMvc.perform(delete(BASE + "/1"))
                .andExpect(status().isNoContent());

        verify(contentService).delete(1L);
    }

    @Test
    @DisplayName("DELETE of missing content returns 404")
    void deleteNotFoundContent() throws Exception {
        doThrow(new ContentNotFoundException(99L)).when(contentService).delete(99L);

        mockMvc.perform(delete(BASE + "/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET search returns a paginated result")
    void search() throws Exception {
        when(contentService.search(any(ContentSearchCriteria.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get(BASE)
                        .param("language", "en")
                        .param("status", "PUBLISHED")
                        .param("key", "home")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].key").value("homepage.banner"))
                .andExpect(jsonPath("$.data.page.size").value(1));
    }

    @Test
    @DisplayName("GET search with an invalid status returns 400")
    void searchInvalidStatus() throws Exception {
        mockMvc.perform(get(BASE).param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
