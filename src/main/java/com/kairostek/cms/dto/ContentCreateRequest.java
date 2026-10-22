package com.kairostek.cms.dto;

import com.kairostek.cms.domain.ContentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for POST /api/v1/cms/contents.
 */
public record ContentCreateRequest(

        @NotBlank(message = "key is required")
        @Size(max = 255, message = "key must not exceed 255 characters")
        String key,

        @NotBlank(message = "title is required")
        @Size(max = 500, message = "title must not exceed 500 characters")
        String title,

        @NotBlank(message = "content is required")
        String content,

        @NotBlank(message = "language is required")
        @Pattern(regexp = "^[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?$", message = "language must be a valid language tag, e.g. 'en' or 'zh-CN'")
        String language,

        @NotNull(message = "status is required")
        ContentStatus status
) {
}
