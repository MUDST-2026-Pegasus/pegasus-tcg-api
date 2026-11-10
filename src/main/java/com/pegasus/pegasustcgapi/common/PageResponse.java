package com.pegasus.pegasustcgapi.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Envelope for paged endpoints. Nothing in the auth module returns a page yet;
 * it lives here so catalogue and listing endpoints share one shape from the start.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        @JsonInclude(JsonInclude.Include.NON_NULL) String correctedQuery) {

    public PageResponse(List<T> items, int page, int size, long totalItems, int totalPages) {
        this(items, page, size, totalItems, totalPages, null);
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        return new PageResponse<>(items, page, size, totalItems, totalPages);
    }

    public PageResponse<T> withCorrectedQuery(String query) {
        return new PageResponse<>(items, page, size, totalItems, totalPages, query);
    }
}
