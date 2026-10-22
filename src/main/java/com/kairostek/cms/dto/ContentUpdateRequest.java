package com.kairostek.cms.dto;

import com.kairostek.cms.domain.ContentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Payload for PUT /api/v1/cms/contents/{id}.
 *
 * <p>{@code expectedVersion} is the version the client last saw; it drives
 * optimistic-lock conflict detection (HTTP 409 on mismatch).
 */
public record ContentUpdateRequest(

        @NotBlank(message = "title is required")
        @Size(max = 500, message = "title must not exceed 500 characters")
        String title,

        @NotBlank(message = "content is required")
        String content,

        @NotNull(message = "status is required")
        ContentStatus status,

        @NotNull(message = "expectedVersion is required")
        @PositiveOrZero(message = "expectedVersion must be zero or positive")
        Long expectedVersion
) {
}
