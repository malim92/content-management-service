package com.kairostek.cms.web;

import com.kairostek.cms.domain.ContentStatus;
import com.kairostek.cms.dto.ApiResponse;
import com.kairostek.cms.dto.ContentCreateRequest;
import com.kairostek.cms.dto.ContentResponse;
import com.kairostek.cms.dto.ContentSearchCriteria;
import com.kairostek.cms.dto.ContentUpdateRequest;
import com.kairostek.cms.service.ContentService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cms/contents")
public class ContentController {

    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContentResponse> create(@Valid @RequestBody ContentCreateRequest request) {
        return ApiResponse.ok("Content created", contentService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<ContentResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(contentService.getById(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ContentResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody ContentUpdateRequest request) {
        return ApiResponse.ok("Content updated", contentService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        contentService.delete(id);
        return ApiResponse.ok("Content deleted", null);
    }

    @GetMapping
    public ApiResponse<Page<ContentResponse>> search(
            @RequestParam(required = false) String language,
            @RequestParam(required = false) ContentStatus status,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ContentSearchCriteria criteria = ContentSearchCriteria.of(language, status, key, page, size);
        return ApiResponse.ok(contentService.search(criteria));
    }
}
